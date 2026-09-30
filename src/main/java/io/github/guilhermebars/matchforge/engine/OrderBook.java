package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.*;
import java.util.*;

/** Single-writer book. Public reads return immutable values, never mutable levels. */
public final class OrderBook {
    public record OpenOrder(OrderId orderId, AccountId accountId, ClientOrderId clientOrderId,
                            Symbol symbol, Side side, long price, long quantity, long reservation) {}
    public record DepthLevel(long price, long quantity, int orderCount) {}
    public record Depth(List<DepthLevel> bids, List<DepthLevel> asks) {
        public Depth { bids = List.copyOf(bids); asks = List.copyOf(asks); }
    }
    static final class PriceLevel {
        final LinkedHashMap<OrderId, OpenOrder> fifo = new LinkedHashMap<>();
    }
    private final Symbol symbol;
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<OrderId, OpenOrder> index = new HashMap<>();

    public OrderBook(Symbol symbol) { this.symbol = Objects.requireNonNull(symbol); }
    public Symbol symbol() { return symbol; }
    public OptionalLong bestBid() { return best(bids); }
    public OptionalLong bestAsk() { return best(asks); }
    private OptionalLong best(TreeMap<Long, PriceLevel> levels) {
        return levels.isEmpty() ? OptionalLong.empty() : OptionalLong.of(levels.firstKey());
    }
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }
    public Optional<OpenOrder> find(OrderId id) { return Optional.ofNullable(index.get(id)); }
    public Depth depth(int n) {
        if (n < 0) throw new IllegalArgumentException("negative depth");
        return new Depth(depth(bids, n), depth(asks, n));
    }
    private List<DepthLevel> depth(TreeMap<Long, PriceLevel> levels, int n) {
        return levels.entrySet().stream().limit(n).map(e -> new DepthLevel(e.getKey(),
                e.getValue().fifo.values().stream().mapToLong(OpenOrder::quantity).reduce(0, Math::addExact),
                e.getValue().fifo.size())).toList();
    }
    public List<OpenOrder> orders() {
        var result = new ArrayList<OpenOrder>();
        bids.values().forEach(l -> result.addAll(l.fifo.values()));
        asks.values().forEach(l -> result.addAll(l.fifo.values()));
        return List.copyOf(result);
    }
    Iterable<OpenOrder> opposite(Side incoming) {
        return () -> levels(incoming == Side.BUY ? Side.SELL : Side.BUY).values().stream()
                .flatMap(l -> l.fifo.values().stream()).iterator();
    }
    private TreeMap<Long, PriceLevel> levels(Side side) { return side == Side.BUY ? bids : asks; }
    void add(OpenOrder order) {
        if (!symbol.equals(order.symbol()) || index.containsKey(order.orderId()))
            throw new IllegalArgumentException("wrong symbol or duplicate order");
        levels(order.side()).computeIfAbsent(order.price(), p -> new PriceLevel()).fifo.put(order.orderId(), order);
        index.put(order.orderId(), order);
    }
    void remove(OrderId id) {
        var order = index.remove(id);
        if (order == null) return;
        var levels = levels(order.side());
        var level = levels.get(order.price());
        level.fifo.remove(id);
        if (level.fifo.isEmpty()) levels.remove(order.price());
    }
    void update(OpenOrder order) {
        var old = index.get(order.orderId());
        if (old == null || old.price() != order.price() || old.side() != order.side())
            throw new IllegalArgumentException("priority-preserving update requires same level");
        levels(order.side()).get(order.price()).fifo.put(order.orderId(), order);
        index.put(order.orderId(), order);
    }
}
