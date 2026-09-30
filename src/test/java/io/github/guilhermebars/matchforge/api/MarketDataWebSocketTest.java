package io.github.guilhermebars.matchforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
class MarketDataWebSocketTest {
    @LocalServerPort
    int port;

    @Test
    void snapshotTradeCoalescedBookAndUnsubscribe() throws Exception {
        var flow = new RestFlow(port);
        flow.fund();
        var received = new LinkedBlockingQueue<JsonNode>();
        var json = new ObjectMapper();
        var client = new StandardWebSocketClient();
        var session = client.execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                                received.add(json.readTree(m.getPayload()));
                            }
                        },
                        "ws://localhost:" + port + "/ws/market-data")
                .get(10, TimeUnit.SECONDS);
        try {
            session.sendMessage(new TextMessage("{\"op\":\"subscribe\",\"channel\":\"book\",\"symbol\":\"BTC-USD\"}"));
            var first = received.poll(10, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.path("type").asText()).isEqualTo("snapshot");
            session.sendMessage(
                    new TextMessage("{\"op\":\"subscribe\",\"channel\":\"trades\",\"symbol\":\"BTC-USD\"}"));
            var second = received.poll(10, TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            long sequence = second.path("sequence").asLong();
            assertThat(sequence).isGreaterThan(first.path("sequence").asLong());
            var sell = flow.post("/orders", flow.sell()).getBody();
            flow.post("/orders", flow.buy());
            boolean trade = false, book = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!trade || !book) && System.nanoTime() < deadline) {
                var message = received.poll(1, TimeUnit.SECONDS);
                if (message == null) continue;
                assertThat(message.path("symbol").asText()).isEqualTo("BTC-USD");
                assertThat(message.path("sequence").asLong()).isGreaterThan(sequence);
                sequence = message.path("sequence").asLong();
                if (message.path("type").asText().equals("trade")) {
                    trade = true;
                    assertThat(message.path("data").path("quantity").asText()).isEqualTo("0.40000000");
                }
                var asks = message.path("data").path("asks");
                if (asks.isArray()
                        && !asks.isEmpty()
                        && asks.get(0).path("quantity").asText().equals("0.60000000")) book = true;
            }
            assertThat(trade).isTrue();
            assertThat(book).isTrue();
            flow.assertSettled(sell.path("orderId").asText());
            for (String channel : new String[] {"book", "trades"})
                session.sendMessage(new TextMessage(
                        "{\"op\":\"unsubscribe\",\"channel\":\"" + channel + "\",\"symbol\":\"BTC-USD\"}"));
            // A different-symbol snapshot is a processing barrier for preceding unsubscribe frames.
            session.sendMessage(new TextMessage("{\"op\":\"subscribe\",\"channel\":\"book\",\"symbol\":\"ETH-USD\"}"));
            JsonNode barrier;
            do {
                barrier = received.poll(5, TimeUnit.SECONDS);
                assertThat(barrier).isNotNull();
            } while (!barrier.path("symbol").asText().equals("ETH-USD"));
            flow.post("/orders", flow.order("buyer", "buy2", "BUY", "0.1"));
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            session.close();
        }
    }
}
