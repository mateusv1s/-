package com.chatestoque;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Persiste os pedidos finalizados em pedidos.csv (Guia Absoluto P4, módulo
 * de Vendas: "Criando Registros — o C do CRUD" + módulo de Produção:
 * "Atualização de Status — o U do CRUD").
 *
 * Sem isso, um pedido confirmado só existia na memória da sessão do
 * navegador: se o servidor reiniciasse, o histórico de vendas da loja
 * inteira sumia. Agora cada pedido confirmado é gravado como uma linha, e
 * cancelamentos atualizam a coluna "status" da linha correspondente.
 */
public class PedidoRepository {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CABECALHO =
            "protocolo,dataHora,nomeUsuario,nomeProduto,itens,totalQuantidade,totalValor,desconto,endereco,pagamento,status";

    private final Path arquivoCsv;

    public PedidoRepository() {
        this(Paths.get(System.getProperty("user.dir"), "pedidos", "pedidos.csv"));
    }

    // Construtor visível para testes: permite apontar para outro arquivo/pasta.
    PedidoRepository(Path arquivoCsv) {
        this.arquivoCsv = arquivoCsv;
        try {
            Files.createDirectories(arquivoCsv.getParent());
            if (!Files.exists(arquivoCsv)) {
                Files.writeString(arquivoCsv, CABECALHO + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException("Nao foi possivel preparar pedidos.csv: " + e.getMessage(), e);
        }
    }

    /**
     * CREATE — registra um pedido confirmado como uma nova linha no CSV.
     * Sempre grava com status "CONFIRMADO".
     */
    public synchronized void registrar(SessaoChat.PedidoFinalizado pedido, String nomeUsuario) {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(arquivoCsv.toFile(), true), StandardCharsets.UTF_8))) {

            writer.write(linhaCsv(pedido, nomeUsuario, "CONFIRMADO"));
            writer.newLine();

        } catch (IOException e) {
            System.err.println("Erro ao registrar pedido [" + pedido.protocolo + "]: " + e.getMessage());
        }
    }

    /**
     * UPDATE — muda o status do pedido (ex: para "CANCELADO") reescrevendo
     * o arquivo inteiro com a linha correspondente atualizada. Como o
     * arquivo de pedidos costuma ser pequeno, reescrever tudo é seguro e
     * simples — o mesmo padrão usado por planilhas CSV comuns.
     */
    public synchronized boolean atualizarStatus(String protocolo, String novoStatus) {
        List<List<String>> registros = lerTodosOsRegistros();
        boolean encontrou = false;

        for (int i = 1; i < registros.size(); i++) { // pula o cabeçalho
            List<String> campos = registros.get(i);
            if (!campos.isEmpty() && campos.get(0).equalsIgnoreCase(protocolo)) {
                // a coluna "status" é a última (índice 10)
                if (campos.size() > 10) {
                    campos.set(10, novoStatus);
                } else {
                    while (campos.size() < 10) campos.add("");
                    campos.add(novoStatus);
                }
                encontrou = true;
                break;
            }
        }

        if (encontrou) escreverTodosOsRegistros(registros);
        return encontrou;
    }

    private List<List<String>> lerTodosOsRegistros() {
        List<List<String>> registros = new ArrayList<>();
        if (!Files.exists(arquivoCsv)) return registros;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(arquivoCsv.toFile()), StandardCharsets.UTF_8))) {
            registros = CsvUtil.lerRegistros(reader);
        } catch (IOException e) {
            System.err.println("Erro ao ler pedidos.csv: " + e.getMessage());
        }
        return registros;
    }

    private void escreverTodosOsRegistros(List<List<String>> registros) {
        Path temporario = arquivoCsv.resolveSibling(arquivoCsv.getFileName() + ".tmp");

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(temporario.toFile()), StandardCharsets.UTF_8))) {

            for (List<String> campos : registros) {
                StringBuilder linha = new StringBuilder();
                for (int i = 0; i < campos.size(); i++) {
                    if (i > 0) linha.append(",");
                    linha.append(CsvUtil.escapar(campos.get(i)));
                }
                writer.write(linha.toString());
                writer.newLine();
            }

        } catch (IOException e) {
            System.err.println("Erro ao salvar pedidos.csv: " + e.getMessage());
            return;
        }

        try {
            Files.move(temporario, arquivoCsv, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Erro ao substituir pedidos.csv: " + e.getMessage());
        }
    }

    private String linhaCsv(SessaoChat.PedidoFinalizado pedido, String nomeUsuario, String status) {
        StringBuilder itens = new StringBuilder();
        for (SessaoChat.ItemPedido item : pedido.itens) {
            if (itens.length() > 0) itens.append(" | ");
            itens.append(item.quantidade).append("x ").append(item.tamanho).append(" ").append(item.cor);
        }

        return String.join(",",
                CsvUtil.escapar(pedido.protocolo),
                CsvUtil.escapar(LocalDateTime.now().format(FMT)),
                CsvUtil.escapar(nomeUsuario != null ? nomeUsuario : ""),
                CsvUtil.escapar(pedido.nomeProduto),
                CsvUtil.escapar(itens.toString()),
                String.valueOf(pedido.totalQuantidade),
                String.valueOf(pedido.totalValor),
                String.valueOf(pedido.desconto),
                CsvUtil.escapar(pedido.endereco),
                CsvUtil.escapar(pedido.pagamento),
                CsvUtil.escapar(status)
        );
    }
}
