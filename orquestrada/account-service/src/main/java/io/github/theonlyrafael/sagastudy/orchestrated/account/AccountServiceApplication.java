package io.github.theonlyrafael.sagastudy.orchestrated.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.github.theonlyrafael.sagastudy.orchestrated.account", "io.github.theonlyrafael.sagastudy.orchestrated.messaging"})
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
