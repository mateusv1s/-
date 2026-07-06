package com.chatestoque;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Controller
public class ChatController {

    private final EstoqueService     estoque      = new EstoqueService();
    private final RespostaService    respostas    = new RespostaService();
    private final PedidoRepository   pedidosRepo  = new PedidoRepository();

    @Autowired
    private HistoricoService historicoService;

    private static final double PRECO_UNITARIO     = 59.90;
    private static final int    MINIMO_DESCONTO    = 30;
    private static final double PERCENTUAL_DESCONTO = 0.10;

    private SessaoChat getSessao(HttpSession http) {
        SessaoChat sessao = (SessaoChat) http.getAttribute("sessao");
        if (sessao == null) {
            sessao = new SessaoChat();
            List<SessaoChat.Mensagem> hist = historicoService.carregar(http.getId());
            for (SessaoChat.Mensagem m : hist) {
                if ("usuario".equals(m.autor())) sessao.registrarUsuario(m.texto());
                else                             sessao.registrarBot(m.texto());
            }
            http.setAttribute("sessao", sessao);
        }
        return sessao;
    }

    @GetMapping("/")
    public String index() { return "chat"; }

    @PostMapping("/mensagem")
    @ResponseBody
    public String mensagem(@RequestParam String texto, HttpSession http) {
        texto = texto.trim();
        SessaoChat sessao = getSessao(http);
        String sid = http.getId();

        sessao.registrarUsuario(texto);
        historicoService.registrar(sid, "usuario", texto);

        // Normaliza (minúsculas + sem acento + sem espaços extras) para toda
        // a lógica de detecção de intenção — ver Texto.normalizar / Guia P4 Semana 2.
        String resposta = processar(texto, Texto.normalizar(texto), sessao, http);

        sessao.registrarBot(resposta);
        historicoService.registrar(sid, "bot", resposta);

        return resposta;
    }

    @GetMapping("/debug-historico")
    @ResponseBody
    public String debugHistorico(HttpSession http) {
        try {
            historicoService.registrar(http.getId(), "debug", "teste");
            return "OK! Pasta: " + historicoService.getCaminhoBase();
        } catch (Exception e) {
            return "ERRO: " + e.getMessage();
        }
    }

    private String processar(String texto, String t, SessaoChat sessao, HttpSession http) {
        try {
            if (texto.isBlank()) return "Oi? Manda alguma coisa pra eu te ajudar!";

            detectarPreferenciaPagamento(t, sessao);

            if (isCancelarPedido(t)) {
                return cancelarPedidoPorProtocolo(t, sessao);
            }

            if (isCancelar(t)) {
                resetarFluxo(sessao);
                return "Beleza, operacao cancelada! O que mais posso fazer por voce?";
            }

            if (isEncerrarSessao(t)) {
                historicoService.deletar(http.getId());
                sessao.limparHistorico();
                http.removeAttribute("sessao");
                return respostas.get("sair");
            }

            if (!sessao.isCpfValidado()) {

                if (sessao.getEstado() == EstadoConversa.AGUARDANDO_COMANDO && isSaudacao(t)) {
                    sessao.setEstado(EstadoConversa.AGUARDANDO_NOME_USUARIO);
                    return "Eae! Seja bem-vindo(a)! Qual e o seu nome?";
                }

                if (sessao.getEstado() == EstadoConversa.AGUARDANDO_NOME_USUARIO) {
                    if (texto.length() < 2 || isSaudacao(t) || texto.matches("\\d+")) {
                        return "Me manda seu nome de verdade pra eu te chamar!";
                    }
                    String nome = capitalize(texto.split("\\s+")[0]);
                    sessao.setNomeUsuario(nome);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_CPF);
                    return "Prazer, " + nome + "! Agora me manda seu CPF pra eu verificar.";
                }

                if (sessao.getEstado() == EstadoConversa.AGUARDANDO_CPF) {
                    if (CPFValidator.validar(t)) {
                        sessao.setCpfValidado(true);
                        sessao.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
                        String nome = sessao.getNomeUsuario() != null ? sessao.getNomeUsuario() : "parceiro";
                        return "CPF verificado! Bora la, " + nome + "!\n\n" + menuTexto();
                    }
                    return respostas.get("cpf_invalido");
                }

                sessao.setEstado(EstadoConversa.AGUARDANDO_NOME_USUARIO);
                return "Me manda seu nome pra comecar o atendimento.";
            }

            if (isSaudacao(t)) {
                String nome = sessao.getNomeUsuario() != null ? ", " + sessao.getNomeUsuario() : "";
                return "Pode falar" + nome + "! O que voce precisa?";
            }

            if (isMudarEndereco(t)) {
                sessao.setEndereco(null);
                sessao.setEstado(EstadoConversa.AGUARDANDO_ENDERECO);
                return "Fechado! Vamos alterar. Me informe o novo endereço de entrega no padrão: **Rua, Número, Bairro**.";
            }

            if (isMenu(t) && sessao.getEstado() == EstadoConversa.AGUARDANDO_COMANDO) {
                return "Claro! Ta na mao:\n\n" + menuTexto();
            }

            if (isVerPedidos(t)) {
                return listarPedidosSessao(sessao);
            }

            if (sessao.getEstado() == EstadoConversa.AGUARDANDO_CONFIRMACAO_ENDERECO) {
                if (isSimResposta(t)) {
                    sessao.setEstado(EstadoConversa.AGUARDANDO_PAGAMENTO);
                    return perguntarPagamento(sessao);
                }
                if (isNaoResposta(t)) {
                    sessao.setEndereco(null);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_ENDERECO);
                    return "Tranquilo! Me manda o novo endereco de entrega no padrao: **Rua, Numero, Bairro**.";
                }
                if (contextoSugereSim(t, sessao)) {
                    sessao.setEstado(EstadoConversa.AGUARDANDO_PAGAMENTO);
                    return "Entendi! " + perguntarPagamento(sessao);
                }
                if (contextoSugereNao(t, sessao)) {
                    sessao.setEndereco(null);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_ENDERECO);
                    return "Ok, me manda o novo endereco (Rua, numero e bairro)!";
                }
                return "Vai enviar pro mesmo endereco (" + sessao.getEndereco() + ")? Responde sim ou nao!";
            }

            if (sessao.getEstado() == EstadoConversa.AGUARDANDO_ENDERECO) {
                if (ehPergunta(t)) {
                    return responderPerguntaContextual(t, sessao)
                            + "\n\nMas me manda o endereco de entrega pra continuar!";
                }

                String validacaoStatus = analisarEnderecoPorPartes(texto, sessao);
                if (!validacaoStatus.equals("OK")) {
                    return validacaoStatus;
                }

                sessao.setEstado(EstadoConversa.AGUARDANDO_PAGAMENTO);
                return "Anotado! " + perguntarPagamento(sessao);
            }

            if (sessao.getEstado() == EstadoConversa.AGUARDANDO_PAGAMENTO) {
                if (isSimResposta(t) && sessao.getPagamentoPreferido() != null) {
                    sessao.setPagamento(sessao.getPagamentoPreferido());
                    return finalizarPedido(sessao);
                }
                String pagamento = detectarPagamento(t);
                if (pagamento != null) {
                    sessao.setPagamento(pagamento);
                    sessao.setPagamentoPreferido(pagamento);
                    return finalizarPedido(sessao);
                }
                if (ehPergunta(t)) {
                    return responderPerguntaContextual(t, sessao)
                            + "\n\nMe diz a forma de pagamento pra fechar! (PIX, cartao ou boleto)";
                }
                return "Nao entendi! Aceitamos: PIX, cartao de credito, cartao de debito e boleto.";
            }

            if (isIntencaoDeCompra(t) && sessao.getEstado() == EstadoConversa.AGUARDANDO_COMANDO) {

                String nomeProduto = extrairNomeProduto(t);
                if (nomeProduto.equals("produto")) {
                    return "Nao entendi qual produto voce quer! Me fala o nome, ex: \"quero 10 camisas GG pretas\".";
                }

                sessao.setNomeProduto(nomeProduto);
                List<SessaoChat.ItemPedido> itens = extrairItensPedido(t);

                if (itens.isEmpty()) {
                    sessao.setItensPedido(new ArrayList<>());
                    sessao.setEstado(EstadoConversa.AGUARDANDO_TAMANHO_COMPRA);
                    return "Qual o tamanho e cor? Exemplo: PP roxa 100, M azul 200";
                }

                sessao.setItensPedido(itens);
                return avancarCompraComItens(sessao);
            }

            if (sessao.getEstado() == EstadoConversa.AGUARDANDO_TAMANHO_COMPRA) {
                List<SessaoChat.ItemPedido> itens = extrairItensPedido(t);
                if (itens.isEmpty()) return "Nao reconheci! Manda assim: GG 30 roxa, G 320 azul";
                sessao.setItensPedido(itens);
                return avancarCompraComItens(sessao);
            }

            switch (sessao.getEstado()) {

                case AGUARDANDO_COMANDO:
                    if (t.equals("1") || t.contains("cadastrar")) {
                        sessao.setEstado(EstadoConversa.AGUARDANDO_ID);
                        return respostas.get("id_produto");
                    }
                    if (t.equals("2") || t.contains("listar")) return listarProdutos();
                    if (t.equals("3") || t.contains("comprar")) {
                        return "Manda bala! Me diz o que quer comprar. Ex: \"quero 2 camisas GG pretas\"";
                    }
                    if (t.contains("continuar") && !sessao.getItensPedido().isEmpty()) {
                        return avancarCompraComItens(sessao);
                    }
                    if (ehPergunta(t)) return responderPerguntaContextual(t, sessao);
                    if (isReclamacao(t)) return "Calma, to aqui pra te ajudar! Me diz o que precisa.";
                    if (isGiria(t)) return respostaGiria(t, sessao);
                    String inferida = tentarInferirIntencao(t, sessao);
                    if (inferida != null) return inferida;
                    return respostas.get("nao_entendi") + "\n\n" + menuTexto();

                case AGUARDANDO_ID:
                    if (!t.matches("\\d+")) return "O ID precisa ser um numero! Manda de novo:";
                    int idDigitado = Integer.parseInt(t);
                    if (estoque.existeId(idDigitado)) {
                        return respostas.get("id_duplicado");
                    }
                    sessao.setIdProduto(idDigitado);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_NOME);
                    return respostas.get("nome_produto");

                case AGUARDANDO_NOME:
                    sessao.setNomeProduto(t);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_TAMANHO);
                    return respostas.get("tamanho_produto");

                case AGUARDANDO_TAMANHO:
                    String tam = texto.toUpperCase().trim();
                    boolean tamValido = tam.equals("PP") || tam.equals("P") || tam.equals("M")
                            || tam.equals("G") || tam.equals("GG") || tam.equals("XG")
                            || tam.equals("XGG") || tam.matches("\\d+");
                    if (!tamValido) return respostas.get("tamanho_invalido");
                    sessao.setTamanhoProduto(tam);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_COR);
                    return respostas.get("cor_produto");

                case AGUARDANDO_COR:
                    sessao.setCorProduto(t);
                    sessao.setEstado(EstadoConversa.AGUARDANDO_QUANTIDADE);
                    return respostas.get("quantidade_produto");

                case AGUARDANDO_QUANTIDADE:
                    if (!t.matches("\\d+")) return respostas.get("quantidade_nao_numerica");
                    int qtd = Integer.parseInt(t);
                    if (qtd <= 0) return respostas.get("quantidade_invalida");
                    // Usa cadastrarComId() em vez de mexer direto na lista: isso persiste
                    // o novo produto no estoque.csv (Semana 3 - Persistencia / CRUD "Create")
                    // e mantém o ID que o usuário escolheu (já validado como único).
                    Produto novo = estoque.cadastrarComId(sessao.getIdProduto(), sessao.getNomeProduto(),
                            sessao.getTamanhoProduto(), sessao.getCorProduto(), qtd);
                    if (novo == null) {
                        sessao.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
                        return "Esse ID acabou de ser usado por outro cadastro. Comeca de novo com 'cadastrar produto'.";
                    }
                    sessao.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
                    sessao.setIdProduto(null); sessao.setNomeProduto(null);
                    sessao.setTamanhoProduto(null); sessao.setCorProduto(null); sessao.setQuantidade(null);
                    return "Produto cadastrado!\n\nID: " + novo.getId() + " | " + novo.getNome()
                            + " | " + novo.getTamanho() + " | " + novo.getCor()
                            + " | Qtd: " + novo.getQuantidade();

                default:
                    sessao.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
                    return "Voltando pro menu...\n\n" + menuTexto();
            }

        } catch (NumberFormatException e) {
            return "Precisa ser um numero! Tenta de novo.";
        } catch (Exception e) {
            return "Erro inesperado: " + e.getMessage();
        }
    }

    private String avancarCompraComItens(SessaoChat sessao) {
        List<SessaoChat.ItemPedido> itens = sessao.getItensPedido();
        String nome = sessao.getNomeProduto() != null ? sessao.getNomeProduto() : "camisa";

        // Falha rápido: avisa sobre estoque insuficiente já aqui, antes de
        // pedir endereço e pagamento — evita o usuário preencher tudo pra
        // só descobrir no final que não tinha estoque suficiente.
        String problemaEstoque = validarEstoqueDisponivel(nome, itens);
        if (problemaEstoque != null) {
            sessao.setEstado(EstadoConversa.AGUARDANDO_TAMANHO_COMPRA);
            return problemaEstoque;
        }

        int totalQtd = 0;
        StringBuilder sb = new StringBuilder();
        for (SessaoChat.ItemPedido item : itens) {
            String corItem = (item.cor == null || "sem cor".equals(item.cor)) ? "" : " " + item.cor;
            sb.append("  ").append(item.quantidade).append("x ")
                    .append(nome).append(" ").append(item.tamanho).append(corItem).append("\n");
            totalQtd += item.quantidade;
        }

        double subtotal  = totalQtd * PRECO_UNITARIO;
        double desconto  = totalQtd >= MINIMO_DESCONTO ? subtotal * PERCENTUAL_DESCONTO : 0;
        double total     = subtotal - desconto;

        sessao.setDescricaoOriginalPedido(sb.toString().trim());

        StringBuilder resumo = new StringBuilder("Anotado! Resumo do pedido:\n\n");
        resumo.append(sb);
        resumo.append("\nValor unitario: R$ ").append(String.format("%.2f", PRECO_UNITARIO));
        resumo.append("\nSubtotal: R$ ").append(String.format("%.2f", subtotal));

        if (desconto > 0) {
            resumo.append("\nDesconto (10%): -R$ ").append(String.format("%.2f", desconto));
            resumo.append("\nTotal com desconto: R$ ").append(String.format("%.2f", total));
        } else {
            resumo.append("\nTotal: R$ ").append(String.format("%.2f", total));
            resumo.append("\n\n(Dica: pedidos com 30+ unidades ganham 10% de desconto!)");
        }
        resumo.append("\n\n");

        if (sessao.getEndereco() != null && !sessao.getEndereco().isBlank()) {
            sessao.setEstado(EstadoConversa.AGUARDANDO_CONFIRMACAO_ENDERECO);
            return resumo + "Vai enviar pro mesmo endereco?\n(" + sessao.getEndereco() + ")";
        }

        sessao.setEstado(EstadoConversa.AGUARDANDO_ENDERECO);
        return resumo + "Para onde enviamos? Por favor, informe no padrão completo: **Rua, Número e Bairro**.";
    }

    /**
     * Confere se dá pra atender todos os itens do pedido com o estoque atual.
     * Retorna null se estiver tudo certo, ou uma mensagem de erro pronta pra
     * devolver ao usuário caso algum item não tenha quantidade suficiente.
     *
     * Combinações de tamanho/cor que ainda não existem no catálogo são
     * consideradas "sob encomenda" e não bloqueiam o pedido — só itens que
     * JÁ existem no estoque com quantidade insuficiente são recusados.
     */
    private String validarEstoqueDisponivel(String nome, List<SessaoChat.ItemPedido> itens) {
        for (SessaoChat.ItemPedido item : itens) {
            String itemCor = item.cor != null ? item.cor : "sem cor";
            Produto existente = estoque.buscar(nome, item.tamanho, itemCor);
            if (existente != null && existente.getQuantidade() < item.quantidade) {
                return "Estoque insuficiente para " + nome + " " + item.tamanho + " " + itemCor
                        + "! Disponivel: " + existente.getQuantidade() + " unidade(s).\n\n"
                        + "Me manda de novo o tamanho e a cor com uma quantidade que a gente tenha, "
                        + "ou ajusta a quantidade. Exemplo: " + item.tamanho + " " + itemCor + " "
                        + existente.getQuantidade();
            }
        }
        return null;
    }

    private String finalizarPedido(SessaoChat sessao) {
        List<SessaoChat.ItemPedido> itens = sessao.getItensPedido();
        String nome        = sessao.getNomeProduto()  != null ? sessao.getNomeProduto()  : "produto";
        String nomeUsuario = sessao.getNomeUsuario()  != null ? sessao.getNomeUsuario()  : "parceiro";

        // FASE 1 — validação: confere de novo se cada item ainda tem estoque
        // suficiente (o estoque pode ter mudado entre o resumo do pedido e
        // agora, se outro cliente comprou o mesmo produto nesse intervalo).
        String problemaEstoque = validarEstoqueDisponivel(nome, itens);
        if (problemaEstoque != null) {
            sessao.setEstado(EstadoConversa.AGUARDANDO_TAMANHO_COMPRA);
            return problemaEstoque;
        }

        // FASE 2 — commit: agora que sabemos que dá pra atender o pedido
        // inteiro, de fato debita o estoque (ou fabrica sob encomenda, se o
        // tamanho/cor ainda não existir no catálogo).
        int totalQtd = 0;
        StringBuilder itensSb = new StringBuilder();
        for (SessaoChat.ItemPedido item : itens) {
            String itemCor = item.cor != null ? item.cor : "sem cor";
            Produto existente = estoque.buscar(nome, item.tamanho, itemCor);

            if (existente == null) {
                // Combinação nova de tamanho/cor: "fabrica sob encomenda" a
                // quantidade exata pedida e já debita na mesma hora.
                estoque.cadastrar(nome, item.tamanho, itemCor, item.quantidade);
            }
            // Neste ponto o produto sempre existe com quantidade suficiente
            // (validado na FASE 1, ou recém-cadastrado com a qtd exata) —
            // então darBaixa aqui sempre retorna SUCESSO.
            estoque.darBaixa(nome, item.tamanho, itemCor, item.quantidade);

            String corLabel = "sem cor".equals(itemCor) ? "" : " " + itemCor;
            itensSb.append("  ").append(item.quantidade).append("x ")
                    .append(nome).append(" ").append(item.tamanho).append(corLabel).append("\n");
            totalQtd += item.quantidade;
        }

        double subtotal = totalQtd * PRECO_UNITARIO;
        double desconto = totalQtd >= MINIMO_DESCONTO ? subtotal * PERCENTUAL_DESCONTO : 0;
        double total    = subtotal - desconto;

        String protocolo = gerarProtocolo(nomeUsuario);

        String corSessaoBalancor = !itens.isEmpty() && itens.get(0).cor != null ? itens.get(0).cor : "sem cor";

        SessaoChat.PedidoFinalizado pedido = new SessaoChat.PedidoFinalizado(
                protocolo, nome, corSessaoBalancor, itens, totalQtd, total, desconto,
                sessao.getEndereco(), sessao.getPagamento());
        sessao.adicionarPedido(pedido);

        // Persiste o pedido em pedidos.csv — assim ele sobrevive a um
        // reinício do servidor, e não fica só na memória da sessão do
        // navegador (Guia P4 Semana 3: "C" do CRUD de Vendas).
        pedidosRepo.registrar(pedido, nomeUsuario);

        sessao.setUltimaQuantidadeComprada(totalQtd);
        sessao.setEstado(EstadoConversa.AGUARDANDO_COMANDO);

        StringBuilder conf = new StringBuilder();
        conf.append("Pedido confirmado, ").append(nomeUsuario).append("!\n");
        conf.append("Protocolo: #").append(protocolo).append("\n\n");
        conf.append(itensSb);
        conf.append("\nValor unitario: R$ ").append(String.format("%.2f", PRECO_UNITARIO)).append("\n");
        conf.append("Subtotal: R$ ").append(String.format("%.2f", subtotal)).append("\n");
        if (desconto > 0)
            conf.append("Desconto (10%): -R$ ").append(String.format("%.2f", desconto)).append("\n")
                    .append("Total com desconto: R$ ").append(String.format("%.2f", total)).append("\n");
        else
            conf.append("Total: R$ ").append(String.format("%.2f", total)).append("\n");
        conf.append("Endereco: ").append(sessao.getEndereco()).append("\n");
        conf.append("Pagamento: ").append(sessao.getPagamento()).append("\n\n");
        conf.append("Guarda o protocolo #").append(protocolo).append(" pra rastrear ou cancelar o pedido!\n");
        conf.append("Valeu pela compra! Qualquer coisa e so falar.");

        sessao.setPagamento(null);
        sessao.setNomeProduto(null); sessao.setCorProduto(null);
        sessao.setItensPedido(new ArrayList<>());
        sessao.setProdutoSelecionado(null); sessao.setQuantidadeDesejada(0);
        sessao.setDescricaoOriginalPedido(null);

        return conf.toString();
    }

    private String analisarEnderecoPorPartes(String texto, SessaoChat sessao) {
        String t = texto.toLowerCase().trim();

        if (sessao.getEndereco() == null || sessao.getEndereco().isBlank()) {
            if (texto.length() < 4 || (!t.contains("rua") && !t.contains("av") && !t.contains("avenida"))) {
                return "Esse endereco parece incompleto. Me informe o nome da **Rua ou Avenida** para podermos continuar.";
            }
            sessao.setEndereco(texto);
        } else {
            sessao.setEndereco(sessao.getEndereco() + ", " + texto);
        }

        String enderecoCompleto = sessao.getEndereco();
        String enderecoLower = enderecoCompleto.toLowerCase();

        boolean temNumero = enderecoLower.matches(".*\\s+\\d+(\\s*|,|$).*") || enderecoLower.contains(" s/n") || enderecoLower.contains(" sem numero");
        if (!temNumero) {
            return "Entendi: \"" + enderecoCompleto + "\". Qual o **numero** da casa/predio?";
        }

        String[] partes = enderecoCompleto.split(",");
        boolean temBairro = partes.length >= 3 || (partes.length == 2 && partes[1].trim().matches(".*\\d+\\s+[a-zA-ZÀ-ú'].*"));

        if (partes.length == 1) {
            temBairro = enderecoLower.matches(".*\\d+\\s+[a-zA-ZÀ-ú'].*");
        }

        if (!temBairro) {
            return "Perfeito! Agora para finalizar o local, me diga o **bairro**.";
        }

        return "OK";
    }

    private String responderPerguntaContextual(String t, SessaoChat sessao) {
        String nome = sessao.getNomeUsuario() != null ? sessao.getNomeUsuario() : "parceiro";

        if (t.contains("meu nome") || t.contains("quem sou eu") || t.contains("como eu chamo")) {
            if (sessao.getNomeUsuario() != null) {
                return "Seu nome é " + sessao.getNomeUsuario() + "!";
            }
            return "Você ainda não me disse seu nome!";
        }

        if (t.contains("endereco da entrega") || t.contains("endereço da entrega") || t.contains("meu endereco") || t.contains("meu endereço") || t.contains("onde vai entregar")) {
            if (sessao.getEndereco() != null && !sessao.getEndereco().isBlank()) {
                return "O endereço de entrega registrado até agora é: **" + sessao.getEndereco() + "**.";
            }
            return "Ainda não temos nenhum endereço registrado para este pedido!";
        }

        if (t.contains("pedido completo") || t.contains("o que eu pedi") || t.contains("meu pedido")) {
            List<SessaoChat.ItemPedido> itens = sessao.getItensPedido();
            if (itens == null || itens.isEmpty()) {
                return "Você não tem nenhum item no carrinho no momento.";
            }
            StringBuilder sb = new StringBuilder("Seu pedido atual tem os seguintes itens:\n\n");
            for (SessaoChat.ItemPedido item : itens) {
                String corItem = (item.cor == null || "sem cor".equals(item.cor)) ? "" : " " + item.cor;
                sb.append("  * ").append(item.quantidade).append("x ")
                        .append(sessao.getNomeProduto() != null ? sessao.getNomeProduto() : "camisa").append(" ")
                        .append(item.tamanho).append(corItem).append("\n");
            }
            return sb.toString();
        }

        if (t.contains("forma de pagamento") || t.contains("como vou pagar") || t.contains("qual pagamento") || t.contains("pagamento eu usei")) {
            if (sessao.getPagamento() != null) {
                return "A forma de pagamento selecionada para este pedido é: **" + sessao.getPagamento() + "**.";
            } else if (sessao.getPagamentoPreferido() != null) {
                return "Você ainda não fechou o pagamento deste pedido, mas sua forma preferida salva é: **" + sessao.getPagamentoPreferido() + "**.";
            }
            return "Ainda não definimos a forma de pagamento. Aceitamos PIX, cartão de crédito, cartão de débito e boleto.";
        }

        if (t.contains("pagamento") || t.contains("pagar") || t.contains("aceita"))
            return "Aceitamos PIX, cartao de credito, cartao de debito e boleto.";
        if (t.contains("entrega") || t.contains("prazo") || t.contains("frete"))
            return "O prazo varia por endereco. Consulte nosso suporte pra mais detalhes!";
        if (t.contains("tem ") || t.contains("estoque") || t.contains("produto"))
            return "Temos camisas P, M, G, GG nas cores rosa, preta e branca. Digita '2' pra ver tudo!";
        if (t.contains("desconto") || t.contains("promocao") || t.contains("promoção")) {
            int descPercent = (int)(PERCENTUAL_DESCONTO * 100);
            return "Pedidos com " + MINIMO_DESCONTO + "+ unidades ganham " + descPercent + "% de desconto automaticamente!";
        }
        if (t.contains("cancelar") || t.contains("devolver") || t.contains("troca"))
            return "Pra cancelar um pedido finalizado, manda: cancelar pedido #PROTOCOLO\nPara limpar o carrinho atual, diga apenas 'cancelar'.";

        String estadoDesc;
        switch (sessao.getEstado()) {
            case AGUARDANDO_ENDERECO:  estadoDesc = "preciso do seu endereco de entrega"; break;
            case AGUARDANDO_PAGAMENTO: estadoDesc = "preciso da forma de pagamento"; break;
            default:                   estadoDesc = "posso te ajudar com compras, cadastro e listagem"; break;
        }
        return "Nao entendi bem, " + nome + "! O que voce quis dizer?\n(" + estadoDesc + ")";
    }

    private String perguntarPagamento(SessaoChat s) {
        if (s.getPagamentoPreferido() != null) {
            return "Deseja utilizar sua forma de pagamento preferida: **" + s.getPagamentoPreferido() + "**? (Responda sim ou informe outra)";
        }
        return "Qual a forma de pagamento? Aceitamos: PIX, cartao de credito, cartao de debito e boleto.";
    }

    private String listarProdutos() {
        List<Produto> lista = estoque.listar();
        if (lista.isEmpty()) return "Nenhum produto em estoque.";
        StringBuilder sb = new StringBuilder("Produtos disponíveis:\n\n");
        for (Produto p : lista) {
            sb.append("ID: ").append(p.getId()).append(" | ").append(p.getNome())
                    .append(" | Tam: ").append(p.getTamanho()).append(" | Cor: ").append(p.getCor())
                    .append(" | Qtd: ").append(p.getQuantidade()).append("\n");
        }
        return sb.toString();
    }

    private String listarPedidosSessao(SessaoChat s) {
        List<SessaoChat.PedidoFinalizado> peds = s.getPedidosFinalizados();
        if (peds.isEmpty()) return "Você não fez nenhum pedido nessa sessão.";
        StringBuilder sb = new StringBuilder("Seus pedidos nesta sessão:\n\n");
        for (SessaoChat.PedidoFinalizado p : peds) {
            sb.append("Protocolo: #").append(p.protocolo).append(" | Total: R$ ").append(String.format("%.2f", p.totalValor))
                    .append(" | Status: ").append(p.cancelado ? "CANCELADO" : "CONFIRMADO").append("\n");
        }
        return sb.toString();
    }

    private String cancelarPedidoPorProtocolo(String t, SessaoChat s) {
        for (SessaoChat.PedidoFinalizado p : s.getPedidosFinalizados()) {
            if (t.contains(p.protocolo.toLowerCase())) {
                if (p.cancelado) return "Esse pedido já consta como cancelado!";
                p.cancelado = true;

                // Devolve as unidades vendidas de volta pro estoque — sem
                // isso, um pedido cancelado deixava o estoque "sumido" pra
                // sempre, mesmo a mercadoria nunca tendo saído da loja.
                for (SessaoChat.ItemPedido item : p.itens) {
                    String itemCor = item.cor != null ? item.cor : "sem cor";
                    estoque.repor(p.nomeProduto, item.tamanho, itemCor, item.quantidade);
                }

                // Reflete o cancelamento no pedidos.csv (Guia P4: "U" do CRUD).
                pedidosRepo.atualizarStatus(p.protocolo, "CANCELADO");

                return "Pedido #" + p.protocolo + " cancelado com sucesso! O estoque já foi atualizado.";
            }
        }

        if (!s.getItensPedido().isEmpty()) {
            List<SessaoChat.ItemPedido> itensAtuais = s.getItensPedido();
            List<SessaoChat.ItemPedido> itensParaRemover = extrairItensPedido(t);

            if (!itensParaRemover.isEmpty()) {
                boolean removeuAlgum = false;
                for (SessaoChat.ItemPedido rem : itensParaRemover) {
                    removeuAlgum |= itensAtuais.removeIf(item ->
                            item.tamanho.equalsIgnoreCase(rem.tamanho) &&
                                    item.cor.equalsIgnoreCase(rem.cor)
                    );
                }

                if (removeuAlgum) {
                    if (itensAtuais.isEmpty()) {
                        resetarFluxo(s);
                        return "Feito! O item foi cancelado e seu carrinho ficou vazio. O que mais posso fazer por voce?";
                    } else {
                        s.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
                        return "Feito! Removi esse produto do seu carrinho.\n\n" + avancarCompraComItens(s);
                    }
                }
            }

            resetarFluxo(s);
            return "Feito! O item foi cancelado e retirei o pedido do seu carrinho. O que mais posso fazer por voce?";
        }

        return "Nao encontrei nenhum pedido ou item ativo para cancelar. Se quiser limpar a tela, digite 'cancelar'.";
    }

    private void detectarPreferenciaPagamento(String t, SessaoChat s) {
        String p = detectarPagamento(t);
        if (p != null && s.getEstado() == EstadoConversa.AGUARDANDO_PAGAMENTO) {
            s.setPagamentoPreferido(p);
        }
    }

    private void resetarFluxo(SessaoChat s) {
        s.setEstado(EstadoConversa.AGUARDANDO_COMANDO);
        s.setNomeProduto(null);
        s.setCorProduto(null);
        s.setItensPedido(new ArrayList<>());
        s.setProdutoSelecionado(null);
        s.setQuantidadeDesejada(0);
        s.setDescricaoOriginalPedido(null);
    }

    private String tentarInferirIntencao(String t, SessaoChat s) {
        return null;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private boolean contextoSugereSim(String t, SessaoChat s) { return false; }
    private boolean contextoSugereNao(String t, SessaoChat s) { return false; }

    private String gerarProtocolo(String nome) {
        return (nome.length() >= 3 ? nome.substring(0, 3).toUpperCase() : "PED") + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    // Tamanhos e cores reconhecidos pelo parser de itens de pedido.
    // Comparação é sempre por palavra INTEIRA (Set.contains), nunca por
    // prefixo — isso evita o bug clássico de "gg" ser lido como "g" só
    // porque "g" também é uma opção válida.
    private static final java.util.Set<String> TAMANHOS_VALIDOS =
            java.util.Set.of("pp", "p", "m", "g", "gg", "xg", "xgg");

    private static final java.util.Set<String> CORES_VALIDAS =
            java.util.Set.of("azul", "roxa", "roxo", "rosa", "preta", "preto", "branca", "branco");

    private String extrairNomeProduto(String t) {
        if (t.contains("camisa") || t.contains("camiseta") || t.contains("camisas")) {
            return "camisa";
        }
        // Fallback: se a frase tiver um número junto de uma cor ou tamanho
        // reconhecido em qualquer ordem (ex: "quero 10 gg pretas"), também
        // tratamos como pedido de camisa.
        for (String palavra : t.split("\\s+")) {
            String limpa = palavra.replaceAll("[^a-z0-9]", "");
            if (limpa.isEmpty()) continue;
            if (TAMANHOS_VALIDOS.contains(limpa) || corValida(limpa) != null) {
                if (t.matches(".*\\d+.*")) return "camisa";
            }
        }
        return "produto";
    }

    /**
     * Extrai os itens de um pedido a partir de texto livre, aceitando
     * quantidade, tamanho e cor em qualquer ordem dentro de cada trecho
     * separado por vírgula — por exemplo, todos estes formatos funcionam:
     *   "2 camisas gg pretas"
     *   "PP roxa 100"
     *   "GG 30 roxa, G 320 azul"
     *   "quero 10 gg pretas e 5 m azuis"
     *
     * A versão anterior usava um único regex com grupos em posição fixa
     * (quantidade, depois cor, depois tamanho) e comparava tamanho por
     * prefixo de caractere — isso fazia "GG" virar "G" e "PP roxa 100"
     * não gerar nenhum item, mesmo sendo o próprio exemplo sugerido pelo
     * bot ao usuário. Tokenizar palavra por palavra e comparar por
     * igualdade exata resolve os dois problemas de uma vez.
     */
    private List<SessaoChat.ItemPedido> extrairItensPedido(String t) {
        List<SessaoChat.ItemPedido> itens = new ArrayList<>();

        // Cada trecho separado por vírgula ou "e" é tratado como um item
        // independente: "GG 30 roxa, G 320 azul" -> dois itens.
        String[] segmentos = t.split(",|\\be\\b");

        for (String segmento : segmentos) {
            Integer quantidade = null;
            String tamanho = null;
            String cor = null;

            for (String palavraOriginal : segmento.trim().split("\\s+")) {
                String palavra = palavraOriginal.replaceAll("[^a-z0-9]", "");
                if (palavra.isEmpty()) continue;

                if (quantidade == null && palavra.matches("\\d+")) {
                    quantidade = Integer.parseInt(palavra);
                    continue;
                }

                if (tamanho == null && TAMANHOS_VALIDOS.contains(palavra)) {
                    tamanho = palavra;
                    continue;
                }

                if (cor == null) {
                    String corEncontrada = corValida(palavra);
                    if (corEncontrada != null) {
                        cor = corEncontrada;
                    }
                }
            }

            // Sem quantidade não dá pra montar um item de pedido válido.
            if (quantidade == null || quantidade <= 0) continue;

            SessaoChat.ItemPedido item = new SessaoChat.ItemPedido();
            item.quantidade = quantidade;
            item.tamanho = tamanho != null ? tamanho.toUpperCase() : "G";
            item.cor = cor != null ? cor : "sem cor";
            itens.add(item);
        }

        return itens;
    }

    /**
     * Verifica se a palavra é uma cor reconhecida (incluindo variações
     * simples de plural, ex: "pretas" -> "preta") e devolve a forma
     * canônica ("roxo" -> "roxa", "branco" -> "branca").
     * Retorna null se a palavra não for uma cor conhecida.
     */
    private String corValida(String palavra) {
        String candidata = palavra;
        if (!CORES_VALIDAS.contains(candidata) && candidata.endsWith("s")) {
            candidata = candidata.substring(0, candidata.length() - 1); // "pretas" -> "preta", "azuis" -> "azui" (nao bate, tratado abaixo)
        }
        if ("azui".equals(candidata)) candidata = "azul"; // "azuis" é plural irregular de "azul"

        if (!CORES_VALIDAS.contains(candidata)) return null;
        return candidata.replace("roxo", "roxa").replace("branco", "branca").replace("preto", "preta");
    }

    private boolean isSaudacao(String t) {
        return t.equals("oi") || t.equals("ola") || t.equals("olá") || t.equals("bom dia")
                || t.equals("boa tarde") || t.equals("boa noite") || t.equals("eae")
                || t.equals("e ai") || t.equals("e aí") || t.equals("iae") || t.equals("fala")
                || t.equals("salve") || t.equals("opa") || t.equals("coe") || t.equals("hey")
                || t.equals("ei") || t.equals("tudo bem") || t.equals("tudo bom")
                || t.equals("qual e") || t.equals("qual é") || t.equals("fala ai")
                || t.equals("fala aí") || t.equals("slc") || t.equals("oxe") || t.equals("uai")
                || t.startsWith("bom dia") || t.startsWith("boa tarde") || t.startsWith("boa noite");
    }

    private boolean isEncerrarSessao(String t) {
        return t.equals("sair") || t.equals("4") || t.equals("tchau") || t.equals("xau")
                || t.equals("bye") || t.equals("fui") || t.equals("vou nessa")
                || t.equals("flw") || t.equals("falou") || t.equals("encerrar");
    }

    private boolean isMenu(String t) {
        return t.contains("menu") || t.contains("ajuda") || t.contains("opcoes") || t.contains("opções");
    }

    private boolean isCancelar(String t) {
        return t.equals("cancelar") || t.equals("cancela") || t.equals("desisto") || t.equals("esquece") || t.contains("nao quero mais");
    }

    private boolean isCancelarPedido(String t) {
        return t.contains("cancelar") && (t.contains("pedido") || t.contains("camisa") || t.contains("camiseta") || t.contains("quero cancelar") || t.contains("as 30") || t.contains("os 30"));
    }

    private boolean isVerPedidos(String t) {
        return t.contains("meus pedidos") || t.contains("ver pedidos") || t.contains("historico") || t.equals("pedidos");
    }

    private boolean isIntencaoDeCompra(String t) {
        if (t.contains("cancelar") || t.contains("cancela") || t.contains("sem o produto")) return false;

        return t.contains("quero") || t.contains("preciso") || t.contains("comprar") || t.contains("pedir") || t.matches(".*\\d+.*(camisa|azul|roxa|branca|g|gg).*");
    }

    private boolean isMudarEndereco(String t) {
        return t.contains("mudar meu endereco") || t.contains("mudar meu endereço")
                || t.contains("alterar meu endereco") || t.contains("alterar meu endereço")
                || t.contains("mudar endereco") || t.contains("mudar endereço")
                || t.contains("alterar endereco") || t.contains("alterar endereço");
    }

    private boolean isSimResposta(String t) {
        return t.equals("sim") || t.equals("s") || t.equals("com certeza") || t.equals("isso");
    }

    private boolean isNaoResposta(String t) {
        return t.equals("nao") || t.equals("não") || t.equals("n");
    }

    private boolean ehPergunta(String t) {
        return t.contains("?") || t.contains("como") || t.contains("onde") || t.contains("qual") || t.contains("quem") || t.contains("quanto");
    }

    private boolean isReclamacao(String t) { return t.contains("ruim") || t.contains("erro") || t.contains("odiei"); }
    private boolean isGiria(String t) { return t.contains("mano") || t.contains("vei") || t.contains("cara"); }
    private String respostaGiria(String t, SessaoChat s) { return "Tranquilo mano! Como posso te ajudar agora?"; }

    private String detectarPagamento(String t) {
        if (t.contains("pix")) return "PIX";
        if (t.contains("credito") || t.contains("crédito")) return "Cartao de Credito";
        if (t.contains("debito") || t.contains("débito")) return "Cartao de Debito";
        if (t.contains("boleto")) return "Boleto";
        return null;
    }

    private String menuTexto() {
        return "1 - Cadastrar Produto\n2 - Listar Produtos\n3 - Comprar\n4 - Sair\n\nEscolha uma opcao ou me diga o que deseja fazer!";
    }
}
