package com.chatestoque;


import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class HistoricoService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Salva na raiz do projeto — o mesmo lugar onde ficam pom.xml e src/.
    // "user.dir" é a propriedade do Java que sempre aponta pro diretório
    // de trabalho do processo, que no IntelliJ é a raiz do projeto.
    private final Path pastaHistoricos =
            Paths.get(System.getProperty("user.dir"), "historicos");

    public HistoricoService() {
        try {
            Files.createDirectories(pastaHistoricos);
            System.out.println("=== HISTORICO: arquivos salvos em -> " + pastaHistoricos.toAbsolutePath() + " ===");
        } catch (IOException e) {
            throw new RuntimeException("Nao foi possivel criar a pasta de historicos: " + e.getMessage(), e);
        }
    }

    public String getCaminhoBase() {
        return pastaHistoricos.toAbsolutePath().toString();
    }

    // ------------------------------------------------------------------ //
    //  Escrita
    // ------------------------------------------------------------------ //

    /**
     * Appenda uma linha no CSV da sessão.
     * Usa append=true — nunca reescreve o arquivo inteiro, só adiciona no fim.
     * Isso é seguro e rápido mesmo com históricos longos.
     *
     * As mensagens do bot costumam ter quebras de linha (\n) de verdade —
     * por isso o campo vai sempre entre aspas (padrão CSV), e a leitura
     * (carregar) usa um parser que entende registros que ocupam várias
     * linhas físicas do arquivo.
     */
    public void registrar(String sessionId, String autor, String mensagem) {
        Path arquivo = caminhoArquivo(sessionId);

        // Cria o cabeçalho se o arquivo ainda não existe.
        boolean novo = !Files.exists(arquivo);

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(arquivo.toFile(), true), // append=true
                        StandardCharsets.UTF_8))) {

            if (novo) {
                writer.write("timestamp,autor,mensagem");
                writer.newLine();
            }

            writer.write(CsvUtil.escapar(LocalDateTime.now().format(FMT)));
            writer.write(",");
            writer.write(CsvUtil.escapar(autor));
            writer.write(",");
            writer.write(CsvUtil.escapar(mensagem));
            writer.newLine();

        } catch (IOException e) {
            // Loga mas não quebra o fluxo — o histórico é auxiliar, não crítico.
            System.err.println("Erro ao registrar historico [" + sessionId + "]: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Leitura — usada ao recarregar a sessão após reinício do servidor
    // ------------------------------------------------------------------ //

    /**
     * Lê todas as mensagens do CSV e retorna como lista de Mensagem.
     * Retorna lista vazia se o arquivo não existir.
     *
     * Usa CsvUtil (em vez de readLine() + split) porque uma mensagem do bot
     * pode conter quebras de linha reais dentro do campo entre aspas — um
     * parser ingênuo baseado em readLine() cortaria essas mensagens ao meio
     * e corromperia o histórico recarregado.
     */
    public List<SessaoChat.Mensagem> carregar(String sessionId) {
        Path arquivo = caminhoArquivo(sessionId);
        List<SessaoChat.Mensagem> mensagens = new ArrayList<>();

        if (!Files.exists(arquivo)) return mensagens;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(arquivo.toFile()), StandardCharsets.UTF_8))) {

            List<List<String>> registros = CsvUtil.lerRegistros(reader);

            boolean primeiraLinha = true;
            for (List<String> campos : registros) {
                if (primeiraLinha) { primeiraLinha = false; continue; }
                if (campos.size() < 3) continue;

                String autor    = campos.get(1).trim();
                String mensagem = campos.get(2);

                mensagens.add(new SessaoChat.Mensagem(autor, mensagem));
            }

        } catch (IOException e) {
            System.err.println("Erro ao carregar historico [" + sessionId + "]: " + e.getMessage());
        }

        return mensagens;
    }

    // ------------------------------------------------------------------ //
    //  Exclusão — chamada ao encerrar o atendimento
    // ------------------------------------------------------------------ //

    /**
     * Deleta o arquivo CSV da sessão.
     * Chamado quando o usuário digita "sair" ou a sessão expira.
     */
    public void deletar(String sessionId) {
        try {
            Files.deleteIfExists(caminhoArquivo(sessionId));
        } catch (IOException e) {
            System.err.println("Erro ao deletar historico [" + sessionId + "]: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Helper
    // ------------------------------------------------------------------ //

    private Path caminhoArquivo(String sessionId) {
        // Sanitiza o sessionId pra evitar path traversal (ex: "../../etc/passwd")
        String idSanitizado = sessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return pastaHistoricos.resolve("historico_" + idSanitizado + ".csv");
    }
}
