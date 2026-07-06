package com.chatestoque;

// Representa um produto no estoque. É só um objeto pra guardar os dados
// e ter as operações básicas de adicionar e remover do estoque.
public class Produto {

    private int id;
    private String nome;
    private String tamanho;
    private String cor;
    private int quantidade;

    // Construtor exige todos os campos de uma vez.
    // A ideia é: produto sem dados não faz sentido existir.
    // O ChatController vai coletando cada info do usuário e só cria o objeto
    // quando tiver tudo pronto.
    public Produto(int id, String nome, String tamanho, String cor, int quantidade) {
        this.id = id;
        this.nome = nome;
        this.tamanho = tamanho;
        this.cor = cor;
        this.quantidade = quantidade;
    }

    // Getters padrão — só leitura.
    // Não tem setters pra nome, tamanho e cor porque esses dados não mudam após o cadastro.
    // Quantidade só muda via adicionar() e remover() abaixo, nunca diretamente.

    public int getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getTamanho() {
        return tamanho;
    }

    public String getCor() {
        return cor;
    }

    public int getQuantidade() {
        return quantidade;
    }

    // Aumenta o estoque. Usado quando um produto é reposto.
    // Não valida se qtd é positivo — quem chama que precisa garantir isso.
    public void adicionar(int qtd) {
        quantidade += qtd;
    }

    // Tenta tirar do estoque. Retorna false se não tiver quantidade suficiente
    // em vez de lançar uma exceção — deixa o código de quem chama decidir o que fazer.
    public boolean remover(int qtd) {

        // Não deixa o estoque ficar negativo.
        if (qtd > quantidade) {
            return false;
        }

        quantidade -= qtd;
        return true;
    }
}