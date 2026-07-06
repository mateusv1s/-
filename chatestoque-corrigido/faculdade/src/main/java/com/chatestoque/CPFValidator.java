package com.chatestoque;

// Classe só pra validar CPF. Não guarda nada, só tem lógica.
// O método é static porque não faz sentido criar um objeto só pra chamar uma função.
// Você chama direto: CPFValidator.validar("12345678901")
public class CPFValidator {

    public static boolean validar(String cpf) {

        // O usuário pode mandar o CPF de vários jeitos: "123.456.789-09", "123 456 789 09", tudo junto...
        // Aqui a gente joga fora tudo que não for número pra comparar só os dígitos.
        cpf = cpf.replaceAll("[^0-9]", "");

        // CPF brasileiro tem sempre 11 dígitos. Se não bater, já rejeita.
        if (!cpf.matches("\\d{11}")) {
            return false;
        }

        // Sequências com todos os dígitos iguais (ex: "111.111.111-11") passam
        // no teste de 11 dígitos, mas não são CPFs válidos.
        if (cpf.chars().distinct().count() == 1) {
            return false;
        }

        return digitoVerificador(cpf, 9) == (cpf.charAt(9) - '0')
                && digitoVerificador(cpf, 10) == (cpf.charAt(10) - '0');
    }

    // Calcula um dos dois dígitos verificadores do CPF.
    // "tamanho" é 9 para o primeiro dígito e 10 para o segundo (que já usa o primeiro no cálculo).
    private static int digitoVerificador(String cpf, int tamanho) {
        int soma = 0;
        int multiplicador = tamanho + 1;

        for (int i = 0; i < tamanho; i++) {
            soma += (cpf.charAt(i) - '0') * multiplicador;
            multiplicador--;
        }

        int resto = soma % 11;
        return (resto < 2) ? 0 : 11 - resto;
    }
}
