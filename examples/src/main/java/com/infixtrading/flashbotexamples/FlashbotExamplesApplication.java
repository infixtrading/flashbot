package com.infixtrading.flashbotexamples;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;

@SpringBootApplication
public class FlashbotExamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashbotExamplesApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            try (Ignite ignite = Ignition.start("config/example-ignite.xml")) {

                System.out.println("Printing size of local node map");
                System.out.println(ignite.cluster().nodeLocalMap().size());

            }
        };
    }
}
