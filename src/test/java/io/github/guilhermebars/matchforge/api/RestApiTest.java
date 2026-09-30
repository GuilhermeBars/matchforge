package io.github.guilhermebars.matchforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest
@ActiveProfiles("memory")
@AutoConfigureMockMvc
class RestApiTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    String buyer, seller;

    @BeforeEach
    void accounts() throws Exception {
        buyer = "b" + UUID.randomUUID();
        seller = "s" + UUID.randomUUID();
        for (String id : new String[] {buyer, seller})
            postJson("/accounts", "{\"id\":\"" + id + "\"}").andExpect(status().isCreated());
        postJson("/accounts/" + buyer + "/deposits", "{\"asset\":\"USD\",\"amount\":\"1000\"}")
                .andExpect(status().isOk());
        postJson("/accounts/" + seller + "/deposits", "{\"asset\":\"BTC\",\"amount\":\"2\"}")
                .andExpect(status().isOk());
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(
                post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String order(String account, String client, String side, String qty) {
        return "{\"accountId\":\"" + account + "\",\"clientOrderId\":\"" + client
                + "\",\"symbol\":\"BTC-USD\",\"side\":\"" + side
                + "\",\"type\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"100\",\"quantity\":\"" + qty + "\"}";
    }

    @Test
    void crossingSettlementReplayAndReadEndpoints() throws Exception {
        String request = order(seller, "sell", "SELL", "1");
        String original = postJson("/orders", request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = json.readTree(original).path("orderId").asText();
        postJson("/orders", order(buyer, "buy", "BUY", "0.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value("0.50000000"))
                .andExpect(jsonPath("$.averagePrice").value("100.00"))
                .andExpect(jsonPath("$.fills.length()").value(1));
        mvc.perform(get("/api/v1/orders/" + id)).andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"));
        String replay = postJson("/orders", request)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json.readTree(replay)).isEqualTo(json.readTree(original));
        postJson("/orders", order(seller, "sell", "SELL", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CLIENT_ORDER_ID"));
        mvc.perform(get("/api/v1/accounts/" + buyer))
                .andExpect(jsonPath("$.balances[?(@.asset=='USD')].available").value("950.0000000000"));
        mvc.perform(get("/api/v1/accounts/" + seller + "/orders?status=OPEN"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/markets/BTC-USD/book?depth=1"))
                .andExpect(jsonPath("$.asks[0].quantity").value("0.50000000"));
        mvc.perform(get("/api/v1/markets/BTC-USD/trades?limit=1"))
                .andExpect(jsonPath("$[0].price").value("100.00"));
        mvc.perform(get("/api/v1/ledger/accounts/" + buyer + "/entries"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(delete("/api/v1/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("USER"));
        postJson("/admin/snapshots", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").isNumber());
    }

    @Test
    void replaceAndCancelReleaseReservation() throws Exception {
        String id = json.readTree(postJson("/orders", order(seller, "replace", "SELL", "1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("orderId")
                .asText();
        mvc.perform(put("/api/v1/orders/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"0.25\",\"price\":\"101\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuantity").value("0.25000000"));
        mvc.perform(delete("/api/v1/orders/" + id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/v1/accounts/" + seller))
                .andExpect(jsonPath("$.balances[0].available").value("2.00000000"))
                .andExpect(jsonPath("$.balances[0].reserved").value("0.00000000"));
        mvc.perform(delete("/api/v1/orders/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ORDER"));
    }

    @Test
    void validationAndStableProblemCodes() throws Exception {
        postJson("/orders", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String amount : new String[] {"123", "\"1e3\"", "\"0.00000000001\"", "\"99999999999999999999\""})
            postJson("/accounts/" + buyer + "/deposits", "{\"asset\":\"USD\",\"amount\":" + amount + "}")
                    .andExpect(status().isBadRequest());
        postJson("/accounts/" + buyer + "/withdrawals", "{\"asset\":\"USD\",\"amount\":\"1001\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        postJson("/accounts/" + buyer + "/withdrawals", "{\"asset\":\"USD\",\"amount\":\"1\"}")
                .andExpect(status().isOk());
        postJson("/orders", order(buyer, "poor", "BUY", "100"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.order.status").value("REJECTED"));
        mvc.perform(get("/api/v1/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ORDER"));
        mvc.perform(get("/api/v1/markets/BTC-USD/book?depth=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/markets/BTC-USD/trades?limit=1001")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + buyer + "/orders?status=garbage")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + buyer + "/orders?status=REJECTED"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void documentationAndMetrics() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Matchforge REST API"));
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("application=\"matchforge\"")));
        for (String endpoint : new String[] {"health", "info", "metrics"})
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isOk());
    }

    @Test
    void cancellationReasonAndRejectedReplayAreStable() throws Exception {
        String fok = order(buyer, "fok", "BUY", "1").replace("GTC", "FOK");
        String cancelled = postJson("/orders", fok)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value("FOK_KILLED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = json.readTree(cancelled).path("orderId").asText();
        mvc.perform(get("/api/v1/orders/" + id)).andExpect(jsonPath("$.reason").value("FOK_KILLED"));
        String replay = postJson("/orders", fok)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json.readTree(replay)).isEqualTo(json.readTree(cancelled));
        String rejected = order(buyer, "rejected", "BUY", "100");
        postJson("/orders", rejected).andExpect(status().isUnprocessableEntity());
        postJson("/orders", rejected)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }
}
