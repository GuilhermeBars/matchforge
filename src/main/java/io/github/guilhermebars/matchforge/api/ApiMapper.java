package io.github.guilhermebars.matchforge.api;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.*;
import io.github.guilhermebars.matchforge.service.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;
import static io.github.guilhermebars.matchforge.api.ApiDtos.*;

@Component
public class ApiMapper {
    private final EngineService engine;
    public ApiMapper(EngineService engine) { this.engine = engine; }
    public InstrumentConfig instrument(String symbol) {
        var id = new Symbol(symbol);
        return engine.readModel().engineState().instruments().stream().filter(i -> i.symbol().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, "UNKNOWN_SYMBOL"));
    }
    public int scale(String asset) {
        var id = new Asset(asset);
        for (var i : engine.readModel().engineState().instruments()) {
            if (i.baseAsset().equals(id)) return i.quantityScale();
            if (i.quoteAsset().equals(id)) return i.priceScale() + i.quantityScale();
        }
        throw new ApiException(404, "UNKNOWN_ASSET");
    }
    public static long parse(String value, int scale) { return Quantity.parse(value, scale).units(); }
    public static String decimal(long value, int scale) { return BigDecimal.valueOf(value, scale).toPlainString(); }
    public Account account(String id) {
        var account = engine.readModel().engineState().accounts().stream().filter(a -> a.accountId().equals(new AccountId(id)))
                .findFirst().orElseThrow(() -> new ApiException(404, "UNKNOWN_ACCOUNT"));
        return new Account(id, account.balances().stream().map(b -> new Balance(b.asset().value(),
                decimal(b.balance().available(), scale(b.asset().value())), decimal(b.balance().reserved(), scale(b.asset().value())))).toList());
    }
    public Command.PlaceOrder request(OrderId id, ExchangeReadModel view) {
        return view.engineState().idempotency().stream().filter(e -> id.equals(e.outcome().orderId()))
                .map(EngineSnapshot.IdempotencyEntry::request).findFirst().orElseThrow(() -> new ApiException(404, "UNKNOWN_ORDER"));
    }
    public Order order(Command.PlaceOrder request, CommandResult.Outcome outcome) {
        var i = instrument(request.symbol().value());
        long filled = outcome.trades().stream().mapToLong(DomainEvent.TradeExecuted::quantity).sum();
        var notional = outcome.trades().stream().map(t -> BigInteger.valueOf(t.price()).multiply(BigInteger.valueOf(t.quantity())))
                .reduce(BigInteger.ZERO, BigInteger::add);
        String avg = filled == 0 ? null : new BigDecimal(notional).divide(BigDecimal.valueOf(filled), 0, RoundingMode.HALF_UP)
                .movePointLeft(i.priceScale()).setScale(i.priceScale()).toPlainString();
        var status = switch (outcome.status()) {
            case OPEN -> filled == 0 ? OrderStatus.NEW : OrderStatus.PARTIALLY_FILLED;
            case FILLED -> OrderStatus.FILLED; case CANCELLED -> OrderStatus.CANCELLED;
            default -> OrderStatus.REJECTED;
        };
        return new Order(outcome.orderId() == null ? null : outcome.orderId().value(), request.accountId().value(),
                request.clientOrderId().value(), request.symbol().value(), status, decimal(outcome.remainingQuantity(), i.quantityScale()),
                decimal(filled, i.quantityScale()), avg, outcome.trades().stream().map(this::fill).toList(),
                outcome.reason() != null ? outcome.reason().name() : outcome.cancelReason() == null ? null : outcome.cancelReason().name());
    }
    public Order order(String id) {
        var view = engine.readModel(); var key = new OrderId(id);
        var outcome = view.orders().get(key);
        if (outcome == null) throw new ApiException(404, "UNKNOWN_ORDER");
        return order(request(key, view), outcome);
    }
    public Fill fill(DomainEvent.TradeExecuted t) {
        var i = instrument(t.symbol().value());
        return new Fill(t.tradeId(), t.symbol().value(), t.makerOrderId().value(), t.takerOrderId().value(),
                decimal(t.price(), i.priceScale()), decimal(t.quantity(), i.quantityScale()), t.header().timestamp());
    }
    public Book book(String symbol, int depth) {
        var i = instrument(symbol); var view = engine.readModel(); var b = view.books().get(i.symbol());
        return new Book(symbol, view.sequence(), levels(b.bids(), i, depth), levels(b.asks(), i, depth));
    }
    private List<Level> levels(List<OrderBook.DepthLevel> levels, InstrumentConfig i, int n) {
        return levels.stream().limit(n).map(l -> new Level(decimal(l.price(), i.priceScale()),
                decimal(l.quantity(), i.quantityScale()), l.orderCount())).toList();
    }
}
