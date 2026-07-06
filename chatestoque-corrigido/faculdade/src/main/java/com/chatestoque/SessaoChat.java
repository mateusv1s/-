package com.chatestoque;

import java.util.ArrayList;
import java.util.List;

public class SessaoChat {

    public static class Mensagem {
        private final String autor;
        private final String texto;
        public Mensagem(String autor, String texto) { this.autor = autor; this.texto = texto; }
        public String autor() { return autor; }
        public String texto() { return texto; }
    }

    public static class ItemPedido {
        public String tamanho;
        public int quantidade;
        public String cor;

        public ItemPedido() {}

        public ItemPedido(String tamanho, int quantidade, String cor) {
            this.tamanho = tamanho;
            this.quantidade = quantidade;
            this.cor = cor;
        }
    }

    public static class PedidoFinalizado {
        public final String protocolo;
        public final String nomeProduto;
        public final String cor;
        public final List<ItemPedido> itens;
        public final int totalQuantidade;
        public final double totalValor;
        public final double desconto;       
        public final String endereco;
        public final String pagamento;
        public boolean cancelado = false;

        public PedidoFinalizado(String protocolo, String nomeProduto, String cor,
                                List<ItemPedido> itens, int totalQuantidade,
                                double totalValor, double desconto,
                                String endereco, String pagamento) {
            this.protocolo      = protocolo;
            this.nomeProduto    = nomeProduto;
            this.cor            = cor;
            this.itens          = new ArrayList<>(itens);
            this.totalQuantidade = totalQuantidade;
            this.totalValor     = totalValor;
            this.desconto       = desconto;
            this.endereco       = endereco;
            this.pagamento      = pagamento;
        }
    }

    private final List<Mensagem> historico = new ArrayList<>();
    private static final int MAX_HISTORICO = 30;

    public void registrarUsuario(String texto) { adicionar("usuario", texto); }
    public void registrarBot(String texto)     { adicionar("bot", texto); }

    private void adicionar(String autor, String texto) {
        if (historico.size() >= MAX_HISTORICO) historico.remove(0);
        historico.add(new Mensagem(autor, texto));
    }

    public List<Mensagem> getHistorico() { return historico; }

    public boolean usuarioJaMencionou(String trecho) {
        for (Mensagem m : historico)
            if (m.autor().equals("usuario") && m.texto().toLowerCase().contains(trecho.toLowerCase()))
                return true;
        return false;
    }

    public String getMensagemAnteriorUsuario() {
        int count = 0;
        for (int i = historico.size() - 1; i >= 0; i--) {
            if (historico.get(i).autor().equals("usuario")) {
                count++;
                if (count == 2) return historico.get(i).texto();
            }
        }
        return "";
    }

    public void limparHistorico() { historico.clear(); }

    private final List<PedidoFinalizado> pedidosFinalizados = new ArrayList<>();

    public void adicionarPedido(PedidoFinalizado p) { pedidosFinalizados.add(p); }
    public List<PedidoFinalizado> getPedidosFinalizados() { return pedidosFinalizados; }

    public PedidoFinalizado buscarPedidoPorProtocolo(String protocolo) {
        for (PedidoFinalizado p : pedidosFinalizados)
            if (p.protocolo.equalsIgnoreCase(protocolo.trim()))
                return p;
        return null;
    }

    private EstadoConversa estado = EstadoConversa.AGUARDANDO_COMANDO;
    private boolean cpfValidado   = false;
    private String nomeUsuario;
    private String descricaoOriginalPedido;

    private Integer idProduto;
    private String nomeProduto;
    private String tamanhoProduto;
    private String corProduto;
    private Integer quantidade;

    private List<ItemPedido> itensPedido = new ArrayList<>();
    private Produto ultimoProdutoComprado;
    private int ultimaQuantidadeComprada = 1;
    private Produto produtoSelecionado;
    private int quantidadeDesejada;

    public int getQuantidadeTotalPedido() {
        int total = 0;
        for (ItemPedido i : itensPedido) total += i.quantidade;
        return total;
    }

    private String endereco;
    private String pagamento;
    private String pagamentoPreferido;

    public EstadoConversa getEstado() { return estado; }
    public void setEstado(EstadoConversa v) { this.estado = v; }

    public boolean isCpfValidado() { return cpfValidado; }
    public void setCpfValidado(boolean v) { this.cpfValidado = v; }

    public String getNomeUsuario() { return nomeUsuario; }
    public void setNomeUsuario(String v) { this.nomeUsuario = v; }

    public String getDescricaoOriginalPedido() { return descricaoOriginalPedido; }
    public void setDescricaoOriginalPedido(String v) { this.descricaoOriginalPedido = v; }

    public Integer getIdProduto() { return idProduto; }
    public void setIdProduto(Integer v) { this.idProduto = v; }

    public String getNomeProduto() { return nomeProduto; }
    public void setNomeProduto(String v) { this.nomeProduto = v; }

    public String getTamanhoProduto() { return tamanhoProduto; }
    public void setTamanhoProduto(String v) { this.tamanhoProduto = v; }

    public String getCorProduto() { return corProduto; }
    public void setCorProduto(String v) { this.corProduto = v; }

    public Integer getQuantidade() { return quantidade; }
    public void setQuantidade(Integer v) { this.quantidade = v; }

    public Produto getUltimoProdutoComprado() { return ultimoProdutoComprado; }
    public void setUltimoProdutoComprado(Produto v) { this.ultimoProdutoComprado = v; }

    public int getUltimaQuantidadeComprada() { return ultimaQuantidadeComprada; }
    public void setUltimaQuantidadeComprada(int v) { this.ultimaQuantidadeComprada = v; }

    public Produto getProdutoSelecionado() { return produtoSelecionado; }
    public void setProdutoSelecionado(Produto v) { this.produtoSelecionado = v; }

    public int getQuantidadeDesejada() { return quantidadeDesejada; }
    public void setQuantidadeDesejada(int v) { this.quantidadeDesejada = v; }

    public List<ItemPedido> getItensPedido() { return itensPedido; }
    public void setItensPedido(List<ItemPedido> v) { this.itensPedido = v; }

    public String getEndereco() { return endereco; }
    public void setEndereco(String v) { this.endereco = v; }

    public String getPagamento() { return pagamento; }
    public void setPagamento(String v) { this.pagamento = v; }

    public String getPagamentoPreferido() { return pagamentoPreferido; }
    public void setPagamentoPreferido(String v) { this.pagamentoPreferido = v; }
}
