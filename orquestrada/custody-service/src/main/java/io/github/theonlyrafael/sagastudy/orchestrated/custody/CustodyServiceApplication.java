package io.github.theonlyrafael.sagastudy.orchestrated.custody;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.github.theonlyrafael.sagastudy.orchestrated.custody", "io.github.theonlyrafael.sagastudy.orchestrated.messaging"})
public class CustodyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustodyServiceApplication.class, args);
    }
}
