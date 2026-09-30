package io.github.guilhermebars.matchforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MatchforgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchforgeApplication.class, args);
    }
}
