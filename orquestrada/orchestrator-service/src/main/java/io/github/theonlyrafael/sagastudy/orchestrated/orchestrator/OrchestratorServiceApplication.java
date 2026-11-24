package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "io.github.theonlyrafael.sagastudy.orchestrated.orchestrator",
        "io.github.theonlyrafael.sagastudy.orchestrated.messaging"
})
public class OrchestratorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorServiceApplication.class, args);
    }
}
