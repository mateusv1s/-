package com.chatestoque;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuida de tudo relacionado ao estoque: criar, listar, buscar, dar baixa e
 * repor produtos — com persistência real em CSV (Guia Absoluto P4, Semana 3
 * "A Memória do Bot: Persistência" + módulo de Estoque).
 *
 * Antes desta versão, o estoque vivia só numa lista em memória: reiniciar o
 * servidor apagava tudo. Agora o arquivo estoque.csv é a fonte da verdade —
 * ele é lido ao iniciar e regravado a cada operação de Create/Update/Delete,
 * exatamente como o pseudocódigo "Dar Baixa no Estoque" do guia descreve.
 */
public class EstoqueService {

    /** Resultado de uma tentativa de dar baixa (venda) no estoque. */
    public enum ResultadoBaixa {
        SUCESSO,
        NAO_ENCONTRADO,
        ESTOQUE_INSUFICIENTE
    }

    private final List<Produto> produtos = new ArrayList<>();
    private final Path arquivoCsv;

    public EstoqueService() {
        this(Paths.get(System.getProperty("user.dir"), "estoque", "estoque.csv"));
    }

    // Construtor visível para testes: permite apontar para outro arquivo/pasta.
    EstoqueService(Path arquivoCsv) {
        this.arquivoCsv = arquivoCsv;
        try {
            Files.createDirectories(arquivoCsv.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Nao foi possivel criar a pasta do estoque: " + e.getMessage(), e);
        }

        if (Files.exists(arquivoCsv)) {
            carregarDoDisco();
        } else {
            carregarEstoqueInicial();
            salvarNoDisco();
        }
    }

    // Catálogo inicial — só é usado na primeiríssima execução, quando ainda
    // não existe estoque.csv no disco.
    private void carregarEstoqueInicial() {
        produtos.add(new Produto(1, "camisa", "P",  "rosa",   8));
        produtos.add(new Produto(2, "camisa", "M",  "rosa",   10));
        produtos.add(new Produto(3, "camisa", "G",  "rosa",   12));
        produtos.add(new Produto(4, "camisa", "GG", "preta",  5));
        produtos.add(new Produto(5, "camisa", "GG", "branca", 3));
    }

    // ------------------------------------------------------------------ //
    //  READ
    // ------------------------------------------------------------------ //

    /**
     * Retorna uma cópia da lista de produtos.
     * Copia proposital: quem recebe não pode corromper o estoque mexendo
     * direto na lista por fora — toda alteração precisa passar pelos métodos
     * desta classe para que seja persistida no CSV.
     */
    public synchronized List<Produto> listar() {
        return new ArrayList<>(produtos);
    }

    /**
     * Busca um produto pela combinação exata de nome + tamanho + cor.
     * Retorna null se não encontrar nada com essa combinação.
     */
    public synchronized Produto buscar(String nome, String tamanho, String cor) {
        for (Produto p : produtos) {
            if (p.getNome().equalsIgnoreCase(nome)
                    && p.getTamanho().equalsIgnoreCase(tamanho)
                    && p.getCor().equalsIgnoreCase(cor)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  CREATE
    // ------------------------------------------------------------------ //

    /**
     * Cadastra um novo produto no estoque e persiste imediatamente.
     * Gera um ID novo automaticamente se o id informado já existir ou for <= 0.
     */
    public synchronized Produto cadastrar(String nome, String tamanho, String cor, int quantidade) {
        int novoId = proximoId();
        Produto novo = new Produto(novoId, nome, tamanho, cor, quantidade);
        produtos.add(novo);
        salvarNoDisco();
        return novo;
    }

    /**
     * Cadastra um produto com um ID específico escolhido pelo usuário.
     * Retorna null se esse ID já estiver em uso — evita duas linhas com a
     * mesma chave primária no estoque.csv.
     */
    public synchronized Produto cadastrarComId(int id, String nome, String tamanho, String cor, int quantidade) {
        if (existeId(id)) return null;
        Produto novo = new Produto(id, nome, tamanho, cor, quantidade);
        produtos.add(novo);
        salvarNoDisco();
        return novo;
    }

    public synchronized boolean existeId(int id) {
        for (Produto p : produtos) {
            if (p.getId() == id) return true;
        }
        return false;
    }

    private int proximoId() {
        int maior = 0;
        for (Produto p : produtos) maior = Math.max(maior, p.getId());
        return maior + 1;
    }

    // ------------------------------------------------------------------ //
    //  UPDATE — baixa (venda) e reposição
    // ------------------------------------------------------------------ //

    /**
     * Tenta dar baixa de "quantidade" unidades no produto (nome/tamanho/cor).
     * Espelha o pseudocódigo "Dar Baixa no Estoque" do guia:
     *   - produto não existe            -> NAO_ENCONTRADO
     *   - existe mas não tem quantidade -> ESTOQUE_INSUFICIENTE (nada é alterado)
     *   - existe e tem quantidade       -> SUCESSO (estoque é debitado e salvo)
     *
     * Nunca "empresta" estoque para completar uma venda: diferente da versão
     * anterior, aqui uma venda maior que o disponível é recusada em vez de
     * inflar magicamente a quantidade em estoque.
     */
    public synchronized ResultadoBaixa darBaixa(String nome, String tamanho, String cor, int quantidade) {
        Produto p = buscar(nome, tamanho, cor);
        if (p == null) return ResultadoBaixa.NAO_ENCONTRADO;
        if (p.getQuantidade() < quantidade) return ResultadoBaixa.ESTOQUE_INSUFICIENTE;

        p.remover(quantidade);
        salvarNoDisco();
        return ResultadoBaixa.SUCESSO;
    }

    /** Repõe estoque de um produto já existente. Persiste imediatamente. */
    public synchronized boolean repor(String nome, String tamanho, String cor, int quantidade) {
        Produto p = buscar(nome, tamanho, cor);
        if (p == null) return false;
        p.adicionar(quantidade);
        salvarNoDisco();
        return true;
    }

    // ------------------------------------------------------------------ //
    //  DELETE
    // ------------------------------------------------------------------ //

    /** Remove um produto do catálogo pelo ID. Persiste imediatamente. */
    public synchronized boolean remover(int id) {
        boolean removeu = produtos.removeIf(p -> p.getId() == id);
        if (removeu) salvarNoDisco();
        return removeu;
    }

    // ------------------------------------------------------------------ //
    //  Persistência
    // ------------------------------------------------------------------ //

    private void carregarDoDisco() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(arquivoCsv.toFile()), StandardCharsets.UTF_8))) {

            List<List<String>> registros = CsvUtil.lerRegistros(reader);
            boolean primeiraLinha = true;

            for (List<String> campos : registros) {
                if (primeiraLinha) { primeiraLinha = false; continue; }
                if (campos.size() < 5) continue;

                try {
                    int id = Integer.parseInt(campos.get(0).trim());
                    String nome = campos.get(1);
                    String tamanho = campos.get(2);
                    String cor = campos.get(3);
                    int quantidade = Integer.parseInt(campos.get(4).trim());
                    produtos.add(new Produto(id, nome, tamanho, cor, quantidade));
                } catch (NumberFormatException ignorado) {
                    // linha corrompida no CSV: ignora e segue carregando o resto
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Erro ao carregar estoque.csv: " + e.getMessage(), e);
        }
    }

    private void salvarNoDisco() {
        Path temporario = arquivoCsv.resolveSibling(arquivoCsv.getFileName() + ".tmp");

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(temporario.toFile()), StandardCharsets.UTF_8))) {

            writer.write("id,nome,tamanho,cor,quantidade");
            writer.newLine();

            for (Produto p : produtos) {
                writer.write(p.getId() + "," + CsvUtil.escapar(p.getNome()) + "," +
                        CsvUtil.escapar(p.getTamanho()) + "," + CsvUtil.escapar(p.getCor()) + "," +
                        p.getQuantidade());
                writer.newLine();
            }

        } catch (IOException e) {
            throw new RuntimeException("Erro ao salvar estoque.csv: " + e.getMessage(), e);
        }

        // Escreve em arquivo temporário e só então substitui o original —
        // evita corromper o CSV se o processo cair no meio da escrita.
        try {
            Files.move(temporario, arquivoCsv, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao substituir estoque.csv: " + e.getMessage(), e);
        }
    }
}
