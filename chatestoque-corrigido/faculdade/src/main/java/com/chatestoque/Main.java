package com.chatestoque;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Ponto de entrada da aplicação. É aqui que tudo começa.
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        // Sobe o servidor. A partir daqui o sistema já tá online e aceitando requisições.
       SpringApplication.run(Main.class, args);
    }
}