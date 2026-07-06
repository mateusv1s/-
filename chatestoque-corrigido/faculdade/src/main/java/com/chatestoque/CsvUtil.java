package com.chatestoque;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitor/escritor de CSV mínimo, mas correto (padrão RFC 4180 simplificado).
 *
 * O parser ingênuo "linha.split(\",\")" lido com readLine() quebra sempre
 * que um campo entre aspas contém uma quebra de linha (como as respostas de
 * várias linhas do respostas.csv) ou uma vírgula. Este utilitário lê
 * caractere a caractere e respeita aspas, mesmo quando um único registro
 * ocupa várias linhas físicas do arquivo.
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /**
     * Lê todos os registros (linhas lógicas) de um CSV a partir de um Reader.
     * Cada registro é uma lista de campos já sem as aspas externas, com
     * aspas duplas escapadas ("") convertidas de volta para uma aspa (").
     */
    public static List<List<String>> lerRegistros(BufferedReader reader) throws IOException {
        List<List<String>> registros = new ArrayList<>();
        List<String> campoAtualRegistro = new ArrayList<>();
        StringBuilder campo = new StringBuilder();

        boolean dentroDeAspas = false;
        boolean registroPendente = false;
        int lido;

        while ((lido = reader.read()) != -1) {
            char c = (char) lido;

            if (dentroDeAspas) {
                if (c == '"') {
                    reader.mark(1);
                    int proximo = reader.read();
                    if (proximo == '"') {
                        campo.append('"'); // aspas duplas escapadas -> uma aspa literal
                    } else {
                        dentroDeAspas = false;
                        if (proximo != -1) reader.reset(); // "devolve" o caractere espiado
                    }
                } else {
                    campo.append(c);
                }
                registroPendente = true;
                continue;
            }

            if (c == '"' && campo.length() == 0) {
                dentroDeAspas = true;
                registroPendente = true;
            } else if (c == ',') {
                campoAtualRegistro.add(campo.toString());
                campo.setLength(0);
                registroPendente = true;
            } else if (c == '\n') {
                campoAtualRegistro.add(campo.toString());
                campo.setLength(0);
                registros.add(campoAtualRegistro);
                campoAtualRegistro = new ArrayList<>();
                registroPendente = false;
            } else if (c == '\r') {
                // ignora: o \n logo em seguida fecha o registro
            } else {
                campo.append(c);
                registroPendente = true;
            }
        }

        // Fecha o último campo/registro pendente (arquivo sem quebra de linha final)
        if (registroPendente || campo.length() > 0 || !campoAtualRegistro.isEmpty()) {
            campoAtualRegistro.add(campo.toString());
            registros.add(campoAtualRegistro);
        }

        return registros;
    }

    /**
     * Escapa um valor para uso seguro como campo de CSV, sempre entre aspas.
     * Aspas internas viram aspas duplas ("").
     */
    public static String escapar(String valor) {
        if (valor == null) return "\"\"";
        return "\"" + valor.replace("\"", "\"\"") + "\"";
    }
}
