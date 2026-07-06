package com.chatestoque;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fallback de IA local via Ollama (Gemma 4).
 *
 * So e chamado pelo ChatController depois que TODAS as regras do chatbot
 * (menu, estoque, compra, pagamento, endereco, etc.) ja tentaram e nao
 * encontraram resposta -- ver responderComIa() em ChatController, chamado
 * em fluxoComandoPrincipal() e responderPerguntaContextual().
 *
 * Usa /api/chat (em vez de /api/generate) porque o Gemma 4 tem suporte
 * nativo ao papel "system" (diferente do Gemma 3), entao a restricao de
 * escopo ("so responde sobre estoque") vai no proprio system prompt, e nao
 * misturada dentro do texto do usuario.
 */
public class EstoqueAiService {

    private static final int MAX_RESPOSTA = 900;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String endpoint;
    private final String modelo;
    private final boolean habilitado;

    public EstoqueAiService() {
        this.endpoint = valorConfig("OLLAMA_URL", "chatestoque.ai.url", "http://localhost:11434/api/chat");
        // gemma3:1b = modelo bem mais leve (815MB) que roda rapido mesmo em
        // CPU pura, sem GPU. gemma4:e2b (a variante "leve" do Gemma 4) ainda
        // e pesado demais pra gerar texto em tempo aceitavel sem GPU -- em
        // notebook comum, a resposta pode demorar dezenas de segundos.
        // Como esse fallback so precisa responder dentro de um escopo bem
        // restrito (estoque), um modelo pequeno da familia Gemma e suficiente.
        // Se um dia rodar em maquina com GPU/mais RAM, troque via env var
        // OLLAMA_MODEL para gemma4:e2b, gemma4:e4b, etc. sem mudar codigo.
        this.modelo = valorConfig("OLLAMA_MODEL", "chatestoque.ai.model", "gemma3:1b");
        this.habilitado = Boolean.parseBoolean(valorConfig("OLLAMA_ENABLED", "chatestoque.ai.enabled", "true"));
    }

    public Optional<String> responder(String mensagemUsuario, SessaoChat sessao, List<Produto> produtos) {
        if (!habilitado || mensagemUsuario == null || mensagemUsuario.isBlank()) return Optional.empty();

        try {
            ObjectNode corpo = mapper.createObjectNode();
            corpo.put("model", modelo);
            corpo.put("stream", false);
            // Mantem o modelo carregado na RAM por 30 min apos o uso, em vez
            // do padrao (que descarrega em poucos minutos). Sem isso, cada
            // nova pergunta ao bot pode pagar de novo o custo de carregar
            // ~7GB do modelo, o que facilmente estoura timeouts em CPU.
            corpo.put("keep_alive", "30m");

            ArrayNode mensagens = corpo.putArray("messages");

            ObjectNode systemMsg = mensagens.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt());

            ObjectNode userMsg = mensagens.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", montarContexto(mensagemUsuario, sessao, produtos));

            ObjectNode options = corpo.putObject("options");
            options.put("temperature", 0.2);
            options.put("num_predict", 150);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("=== OLLAMA: status " + response.statusCode() + " ao chamar " + endpoint
                        + " com modelo '" + modelo + "' -> corpo: " + response.body());
                return Optional.empty();
            }

            JsonNode json = mapper.readTree(response.body());
            // /api/chat devolve { "message": { "role": "assistant", "content": "..." }, ... }
            String texto = json.path("message").path("content").asText("").trim();

            if (texto.isBlank()) {
                System.err.println("=== OLLAMA: resposta vazia. Corpo bruto recebido: " + response.body());
                return Optional.empty();
            }

            if (!respostaSegura(texto)) {
                System.err.println("=== OLLAMA: resposta bloqueada pelo filtro respostaSegura(). Texto: " + texto);
                return Optional.empty();
            }

            if (texto.length() > MAX_RESPOSTA) {
                texto = texto.substring(0, MAX_RESPOSTA).trim();
            }
            return Optional.of(texto);
        } catch (Exception e) {
            System.err.println("=== OLLAMA: excecao ao chamar " + endpoint + " com modelo '" + modelo + "': "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Instrucoes fixas de papel e escopo. Fica separado do contexto porque
     * o Gemma 4 trata o "system" como prioridade mais alta e mais estavel
     * do que texto dentro da mensagem do usuario -- e essa e a parte que
     * garante que a IA "sabe que faz parte do estoque" e nao responde
     * qualquer coisa fora disso.
     */
    private String systemPrompt() {
        return """
                Voce e o assistente de fallback de um chatbot de controle de estoque de uma loja.
                Voce NAO e um assistente geral: so existe para ajudar com estoque, produtos,
                pedidos, pagamento, endereco e cancelamento desta loja.

                Responda sempre em portugues do Brasil, sem markdown pesado, em no maximo 8 linhas.

                Regras obrigatorias:
                - Nunca invente estoque, preco, protocolo, prazo ou status.
                - Use somente os dados de estoque/pedido informados no contexto abaixo.
                - Se o usuario quiser comprar, peca quantidade, produto, tamanho e cor quando faltar algo.
                - Cores aceitas no sistema: azul, roxa, rosa, preta, branca.
                - Tamanhos aceitos no sistema: PP, P, M, G, GG, XG, XGG.
                - Se a cor nao existir no sistema, diga que nao reconheceu e ofereca as cores aceitas.
                - Para acoes reais (cadastrar, comprar, cancelar), oriente o usuario a escrever no
                  formato que o sistema entende -- voce mesmo nunca executa a acao.
                - Nunca diga que cadastrou, cancelou, reservou ou confirmou algo: isso e feito
                  apenas pelas regras do chatbot, nunca por voce.
                - Se a pergunta do usuario nao tiver nenhuma relacao com estoque, produtos, pedidos,
                  pagamento, endereco ou cancelamento, recuse educadamente e redirecione o usuario
                  de volta para esses assuntos. Nao responda perguntas gerais, de conhecimento
                  externo, opinioes, ou qualquer coisa fora do escopo da loja.
                """;
    }

    /** Contexto dinamico (estado da sessao + estoque atual + pergunta) que muda a cada chamada. */
    private String montarContexto(String mensagemUsuario, SessaoChat sessao, List<Produto> produtos) {
        StringBuilder estoque = new StringBuilder();
        if (produtos.isEmpty()) {
            estoque.append("Nenhum produto cadastrado.");
        } else {
            int limite = Math.min(produtos.size(), 40);
            for (int i = 0; i < limite; i++) {
                Produto p = produtos.get(i);
                estoque.append("- ID ").append(p.getId())
                        .append(": ").append(p.getNome())
                        .append(", tamanho ").append(p.getTamanho())
                        .append(", cor ").append(p.getCor())
                        .append(", qtd ").append(p.getQuantidade())
                        .append("\n");
            }
        }

        String pedidoAtual = "vazio";
        if (sessao.getItensPedido() != null && !sessao.getItensPedido().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SessaoChat.ItemPedido item : sessao.getItensPedido()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(item.quantidade).append("x ")
                        .append(sessao.getNomeProduto() == null ? "produto" : sessao.getNomeProduto())
                        .append(" tam=").append(item.tamanho == null ? "nao informado" : item.tamanho)
                        .append(" cor=").append(item.cor == null ? "nao informada" : item.cor);
            }
            pedidoAtual = sb.toString();
        }

        return """
                Estado atual: %s
                Nome do cliente: %s
                Pedido atual: %s
                Endereco atual: %s
                Pagamento preferido: %s

                Estoque atual:
                %s

                Mensagem do usuario:
                %s
                """.formatted(
                sessao.getEstado(),
                sessao.getNomeUsuario() == null ? "nao informado" : sessao.getNomeUsuario(),
                pedidoAtual,
                sessao.getEndereco() == null ? "nao informado" : sessao.getEndereco(),
                sessao.getPagamentoPreferido() == null ? "nao informado" : sessao.getPagamentoPreferido(),
                estoque,
                mensagemUsuario
        );
    }

    private boolean respostaSegura(String texto) {
        if (texto == null || texto.isBlank()) return false;
        String t = Texto.normalizar(texto);
        return !t.contains("pedido confirmado")
                && !t.contains("cadastro realizado")
                && !t.contains("produto cadastrado")
                && !t.contains("estoque atualizado")
                && !t.contains("pedido cancelado com sucesso");
    }

    private String valorConfig(String env, String prop, String padrao) {
        String valor = System.getenv(env);
        if (valor != null && !valor.isBlank()) return valor.trim();
        valor = System.getProperty(prop);
        if (valor != null && !valor.isBlank()) return valor.trim();
        return padrao;
    }
}