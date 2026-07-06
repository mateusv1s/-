package com.chatestoque;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrega as mensagens do sistema a partir de um arquivo CSV (respostas.csv).
 * A ideia é centralizar os textos fora do código — assim dá pra editar as
 * mensagens sem precisar recompilar nada (a "base de conhecimento" do Guia
 * Absoluto P4, Semana 1).
 *
 * Usa o CsvUtil para o parsing, pois várias respostas ocupam mais de uma
 * linha física do arquivo (ex: "saudacao"), e um split(",") ingênuo com
 * leitura linha a linha quebraria esses registros.
 */
public class RespostaService {

    // Dicionário chave -> mensagem. Ex: "menu" -> "1 - Cadastrar Produto\n..."
    private final Map<String, String> respostas = new HashMap<>();

    // Assim que o objeto é criado, já carrega o CSV.
    // Se o arquivo não existir ou tiver erro, a aplicação não sobe — melhor do que subir quebrada.
    public RespostaService() {
        carregarCSV();
    }

    private void carregarCSV() {
        try {
            // Procura o arquivo dentro da pasta resources/ do projeto.
            ClassPathResource resource = new ClassPathResource("respostas.csv");

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                List<List<String>> registros = CsvUtil.lerRegistros(br);

                boolean primeiraLinha = true;
                for (List<String> campos : registros) {
                    // A primeira linha é o cabeçalho (chave,mensagem) — pula ela.
                    if (primeiraLinha) {
                        primeiraLinha = false;
                        continue;
                    }

                    // Linha mal formada (sem os dois campos esperados) — ignora e continua.
                    if (campos.size() < 2) continue;

                    String chave = campos.get(0).trim();
                    String mensagem = campos.get(1);

                    if (chave.isEmpty()) continue;

                    respostas.put(chave, mensagem);
                }
            }

        } catch (Exception e) {
            // Qualquer problema com o arquivo vira um erro que para a aplicação na inicialização.
            throw new RuntimeException("Erro ao carregar respostas.csv: " + e.getMessage(), e);
        }
    }

    // Busca uma mensagem pela chave. Se a chave não existir, avisa no retorno
    // em vez de retornar null — ajuda a encontrar chaves erradas durante o desenvolvimento.
    public String get(String chave) {
        return respostas.getOrDefault(chave, "[Resposta não encontrada para: " + chave + "]");
    }

    // Permite checar se uma chave existe, sem gerar o texto de fallback.
    public boolean contem(String chave) {
        return respostas.containsKey(chave);
    }
}
