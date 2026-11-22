package io.github.theonlyrafael.sagastudy.orchestrated.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.github.theonlyrafael.sagastudy.orchestrated.order", "io.github.theonlyrafael.sagastudy.orchestrated.messaging"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
