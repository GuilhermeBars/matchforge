package io.github.guilhermebars.matchforge.api;

import io.github.guilhermebars.matchforge.MatchforgeApplication;
import io.github.guilhermebars.matchforge.service.EngineService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class PostgresRestRecoveryTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(MatchforgeApplication.class).profiles("postgres").run(
                "--server.port=0", "--spring.datasource.url="+POSTGRES.getJdbcUrl(), "--spring.datasource.username="+POSTGRES.getUsername(),
                "--spring.datasource.password="+POSTGRES.getPassword(), "--matchforge.snapshot.interval=1000", "--matchforge.journal.type=postgres");
    }
    @Test void restSettlementSnapshotTailRecoveryAndOriginalIdempotentResponse() {
        com.fasterxml.jackson.databind.JsonNode original;
        io.github.guilhermebars.matchforge.service.ExchangeReadModel expected;
        try (var context = start()) {
            var flow = new RestFlow(context.getWebServer().getPort()); flow.fund();
            original = flow.post("/orders", flow.sell()).getBody();
            flow.post("/admin/snapshots", Map.of()); // Sell in snapshot, fill only in journal tail.
            flow.post("/orders", flow.buy()); flow.assertSettled(original.path("orderId").asText());
            expected = context.getBean(EngineService.class).readModel();
        }
        try (var context = start()) {
            assertThat(context.getBean(EngineService.class).readModel()).isEqualTo(expected);
            var flow = new RestFlow(context.getWebServer().getPort()); flow.assertSettled(original.path("orderId").asText());
            var retry = flow.post("/orders", flow.sell());
            assertThat(retry.getStatusCode().value()).isEqualTo(200);
            assertThat(retry.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
            assertThat(retry.getBody()).isEqualTo(original);
            assertThat(flow.get("/markets/BTC-USD/trades").size()).isEqualTo(1);
        }
    }
}
