package io.github.theonlyrafael.sagastudy.orchestrated.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.github.theonlyrafael.sagastudy.orchestrated.settlement", "io.github.theonlyrafael.sagastudy.orchestrated.messaging"})
public class SettlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
