package com.chatestoque;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * "A peneira" do sistema (Guia Absoluto P4, Semana 2 - Normalização de Texto).
 *
 * Garante que "PAGAR", "pagar" e "págár" sejam entendidos como a mesma coisa.
 * Sem isso, o Bot trata "endereço" e "endereco" como palavras diferentes,
 * e qualquer variação de maiúscula/minúscula ou acentuação quebra a detecção
 * de intenção.
 */
public final class Texto {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}");
    private static final Pattern ESPACOS_EXTRAS = Pattern.compile("\\s+");

    private Texto() {
        // classe utilitária, não deve ser instanciada
    }

    /**
     * Normaliza uma string para comparação segura:
     * 1. minúsculas
     * 2. remove acentuação (á -> a, ç -> c, õ -> o...)
     * 3. colapsa espaços extras
     * 4. remove espaços nas pontas
     *
     * Nunca retorna null: entrada nula vira string vazia.
     */
    public static String normalizar(String entrada) {
        if (entrada == null) return "";

        String semAcento = removerAcentos(entrada.toLowerCase());
        String semEspacosExtras = ESPACOS_EXTRAS.matcher(semAcento).replaceAll(" ");

        return semEspacosExtras.trim();
    }

    /**
     * Remove apenas a acentuação, preservando maiúsculas/minúsculas originais.
     * Usa decomposição Unicode (NFD): separa a letra base do sinal diacrítico
     * e depois descarta o sinal.
     */
    public static String removerAcentos(String entrada) {
        if (entrada == null) return "";
        String decomposto = Normalizer.normalize(entrada, Normalizer.Form.NFD);
        return MARCAS_DIACRITICAS.matcher(decomposto).replaceAll("");
    }
}
