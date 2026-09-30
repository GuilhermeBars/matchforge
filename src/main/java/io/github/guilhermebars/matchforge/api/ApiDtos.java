package io.github.guilhermebars.matchforge.api;

import io.github.guilhermebars.matchforge.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ApiDtos {
    private ApiDtos() {}
    public record CreateAccount(@Pattern(regexp="[A-Za-z0-9_-]{1,128}") String id) {}
    public record Funds(@NotBlank String asset,
                        @NotBlank @Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") @Schema(example="1000.00", type="string") String amount) {}
    @Schema(description="Exact decimal strings; market buys require quoteBudget in quote asset units")
    public record PlaceOrder(@NotBlank String accountId, @NotBlank String clientOrderId,
            @NotBlank @Schema(example="BTC-USD") String symbol, @NotNull Side side, @NotNull OrderType type,
            @NotNull TimeInForce timeInForce,
            @Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") @Schema(example="100.00", type="string") String price,
            @NotBlank @Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") @Schema(example="0.01000000", type="string") String quantity,
            @Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") @Schema(example="10.00", type="string") String quoteBudget) {}
    @Schema(description="Omitted fields retain their values; quantity is the new remaining quantity")
    public record ReplaceOrder(@Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") String price,
                               @Pattern(regexp="[0-9]+(?:\\.[0-9]+)?") String quantity) {}
    public record Balance(String asset, String available, String reserved) {}
    public record Account(String id, List<Balance> balances) {}
    public record Fill(long tradeId, String symbol, String makerOrderId, String takerOrderId,
                       String price, String quantity, Instant timestamp) {}
    public enum OrderStatus { NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }
    public record Order(String orderId, String accountId, String clientOrderId, String symbol,
                        OrderStatus status, String remainingQuantity, String filledQuantity,
                        @Schema(description="Weighted mean rounded HALF_UP to instrument price scale; null without fills") String averagePrice,
                        List<Fill> fills, String reason) {}
    public record Level(String price, String quantity, int orderCount) {}
    public record Book(String symbol, long sequence, List<Level> bids, List<Level> asks) {}
    public record Market(String symbol, String baseAsset, String quoteAsset, String tickSize, String lotSize,
                         int priceScale, int quantityScale) {}
    public record Entry(long sequence, Instant timestamp, String reference, String asset, String amount) {}
    public record Snapshot(long sequence) {}
}
