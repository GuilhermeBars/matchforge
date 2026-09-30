package io.github.guilhermebars.matchforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

final class RestFlow {
    final RestClient http;
    RestFlow(int port) { http = RestClient.create("http://localhost:" + port); }
    ResponseEntity<JsonNode> post(String path, Object body) {
        return http.post().uri("/api/v1"+path).body(body).retrieve().toEntity(JsonNode.class);
    }
    JsonNode get(String path) { return http.get().uri("/api/v1"+path).retrieve().body(JsonNode.class); }
    Map<String,Object> sell() { return order("seller", "sell", "SELL", "1"); }
    Map<String,Object> buy() { return order("buyer", "buy", "BUY", "0.4"); }
    Map<String,Object> order(String account, String client, String side, String qty) {
        return Map.of("accountId",account,"clientOrderId",client,"symbol","BTC-USD","side",side,"type","LIMIT",
                "timeInForce","GTC","price","100.00","quantity",qty);
    }
    void fund() {
        post("/accounts", Map.of("id","seller")); post("/accounts", Map.of("id","buyer"));
        post("/accounts/seller/deposits", Map.of("asset","BTC","amount","2"));
        post("/accounts/buyer/deposits", Map.of("asset","USD","amount","1000"));
    }
    void assertSettled(String id) {
        assertThat(get("/orders/"+id).path("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(get("/markets/BTC-USD/book").path("asks").get(0).path("quantity").asText()).isEqualTo("0.60000000");
        assertThat(get("/markets/BTC-USD/trades").size()).isEqualTo(1);
        assertThat(get("/ledger/accounts/buyer/entries").size()).isEqualTo(3);
        var balances = get("/accounts/buyer").path("balances");
        assertThat(balances.findValuesAsText("available")).contains("960.0000000000", "0.40000000");
        assertThat(balances.findValuesAsText("reserved")).containsOnly("0.0000000000", "0.00000000");
    }
}
