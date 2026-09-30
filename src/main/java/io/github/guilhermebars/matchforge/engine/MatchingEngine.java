package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.risk.*;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import java.util.*;
import static io.github.guilhermebars.matchforge.engine.Command.*;
import static io.github.guilhermebars.matchforge.engine.CommandResult.*;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.*;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.*;

/** Deterministic single writer. The caller owns sequencing, timestamps and synchronization. */
public final class MatchingEngine {
    private record Key(AccountId accountId, ClientOrderId clientOrderId) {}
    private record Fill(OrderBook.OpenOrder maker, long quantity) {}
    private record Plan(List<Fill> fills, long remaining, boolean budgetLimited) {}
    private static final class Rejected extends RuntimeException {
        final RejectionReason reason;
        Rejected(RejectionReason reason) { this.reason = reason; }
    }
    private final LinkedHashMap<Symbol, InstrumentConfig> instruments = new LinkedHashMap<>();
    private final LinkedHashMap<Symbol, OrderBook> books = new LinkedHashMap<>();
    private final Map<Asset, Integer> scales = new LinkedHashMap<>();
    private final LinkedHashMap<Key, EngineSnapshot.IdempotencyEntry> idempotency = new LinkedHashMap<>();
    private AccountBook accounts = new AccountBook();
    private final Ledger ledger = new Ledger();
    private long nextOrderId = 1, nextTradeId = 1, lastSequence;

    public MatchingEngine(List<InstrumentConfig> configs) {
        for (var i : configs) {
            if (instruments.putIfAbsent(i.symbol(), i) != null) throw new IllegalArgumentException("duplicate instrument");
            registerScale(i.baseAsset(), i.quantityScale());
            registerScale(i.quoteAsset(), Math.addExact(i.priceScale(), i.quantityScale()));
            books.put(i.symbol(), new OrderBook(i.symbol()));
        }
        if (instruments.isEmpty()) throw new IllegalArgumentException("no instruments");
    }
    private void registerScale(Asset asset, int scale) {
        var old = scales.putIfAbsent(asset, scale);
        if (scale > 18 || old != null && old != scale) throw new IllegalArgumentException("inconsistent asset precision");
    }
    public OrderBook book(Symbol symbol) { return Objects.requireNonNull(books.get(symbol), "unknown symbol"); }
    public AccountBook.Balance balance(AccountId id, Asset asset) { return accounts.balance(id, asset); }
    public List<Ledger.Transaction> ledgerTransactions() { return ledger.transactions(); }
    public long lastSequence() { return lastSequence; }

    public CommandResult process(CommandEnvelope envelope) {
        if (envelope.sequence() != Math.addExact(lastSequence, 1))
            throw new IllegalArgumentException("sequence must be contiguous");
        lastSequence = envelope.sequence();
        var command = envelope.command();
        var header = new Header(envelope.sequence(), envelope.timestamp());
        var events = new ArrayList<DomainEvent>();
        if (command instanceof PlaceOrder p) {
            var previous = idempotency.get(new Key(p.accountId(), p.clientOrderId()));
            if (previous != null) {
                if (previous.request().equals(p)) return new CommandResult(previous.outcome(), List.of(), true);
                return new CommandResult(new Outcome(Status.CONFLICT, previous.outcome().orderId(),
                        DUPLICATE_CLIENT_ORDER_ID, 0, List.of()), List.of(), false);
            }
        }
        Outcome outcome;
        try {
            outcome = switch (command) {
                case CreateAccount c -> create(c, header, events);
                case Deposit d -> fund(d.accountId(), d.asset(), d.amount(), true, header, events);
                case Withdraw w -> fund(w.accountId(), w.asset(), w.amount(), false, header, events);
                case PlaceOrder p -> place(p, header, events);
                case CancelOrder c -> cancel(c, header, events);
                case ReplaceOrder r -> replace(r, header, events);
            };
        } catch (Rejected rejected) {
            events.add(new OrderRejected(header, accountOf(command), rejected.reason));
            outcome = new Outcome(Status.REJECTED, null, rejected.reason, 0, List.of());
        }
        if (command instanceof PlaceOrder p && p.accountId() != null && p.clientOrderId() != null)
            idempotency.put(new Key(p.accountId(), p.clientOrderId()), new EngineSnapshot.IdempotencyEntry(p, outcome));
        return new CommandResult(outcome, events, false);
    }
    private AccountId accountOf(Command c) {
        return switch (c) {
            case CreateAccount x -> x.accountId(); case Deposit x -> x.accountId();
            case Withdraw x -> x.accountId(); case PlaceOrder x -> x.accountId();
            case CancelOrder x -> x.accountId(); case ReplaceOrder x -> x.accountId();
        };
    }
    private void require(boolean condition, RejectionReason reason) { if (!condition) throw new Rejected(reason); }
    private void known(AccountId id) { require(accounts.contains(id), UNKNOWN_ACCOUNT); }
    private Outcome ok() { return new Outcome(Status.OK, null, null, 0, List.of()); }
    private Outcome create(CreateAccount c, Header h, List<DomainEvent> events) {
        require(c.accountId() != null && !c.accountId().equals(Ledger.EXTERNAL), INVALID_ORDER);
        require(!accounts.contains(c.accountId()), ACCOUNT_EXISTS);
        accounts.create(c.accountId()); events.add(new AccountCreated(h, c.accountId())); return ok();
    }
    private Outcome fund(AccountId id, Asset asset, long amount, boolean deposit, Header h, List<DomainEvent> events) {
        known(id); require(scales.containsKey(asset), UNKNOWN_ASSET); require(amount > 0, INVALID_AMOUNT);
        if (deposit) {
            try { Math.addExact(accounts.totals().getOrDefault(asset, 0L), amount); }
            catch (ArithmeticException e) { throw new Rejected(NUMERIC_OVERFLOW); }
        } else require(accounts.balance(id, asset).available() >= amount, INSUFFICIENT_FUNDS);
        long delta = deposit ? amount : -amount;
        accounts.change(id, asset, delta, 0);
        events.add(deposit ? new FundsDeposited(h, id, asset, amount) : new FundsWithdrawn(h, id, asset, amount));
        post(h, deposit ? "deposit" : "withdraw", List.of(new Ledger.Posting(id, asset, delta),
                new Ledger.Posting(Ledger.EXTERNAL, asset, -delta)), events);
        return ok();
    }
    private long validate(PlaceOrder p, long credit) {
        known(p.accountId());
        var reason = RiskManager.validate(p, instruments.get(p.symbol()));
        if (reason != null) throw new Rejected(reason);
        long reserve;
        try { reserve = RiskManager.reservation(p); }
        catch (ArithmeticException e) { throw new Rejected(NUMERIC_OVERFLOW); }
        require(reserve <= Math.addExact(accounts.balance(p.accountId(), reserveAsset(p)).available(), credit), INSUFFICIENT_FUNDS);
        return reserve;
    }
    private Asset reserveAsset(PlaceOrder p) {
        var i = instruments.get(p.symbol()); return p.side() == Side.BUY ? i.quoteAsset() : i.baseAsset();
    }
    private Plan plan(PlaceOrder p) {
        var fills = new ArrayList<Fill>();
        long remaining = p.quantity();
        long budget = p.type() == OrderType.MARKET && p.side() == Side.BUY ? p.quoteBudget() : Long.MAX_VALUE;
        long lot = instruments.get(p.symbol()).lotSize().units();
        boolean budgetLimited = false;
        for (var maker : book(p.symbol()).opposite(p.side())) {
            if (remaining == 0) break;
            if (p.type() == OrderType.LIMIT && (p.side() == Side.BUY ? maker.price() > p.price() : maker.price() < p.price())) break;
            long quantity = Math.min(remaining, maker.quantity());
            if (p.type() == OrderType.MARKET && p.side() == Side.BUY) {
                long affordable = budget / maker.price() / lot * lot;
                if (affordable < quantity) { quantity = affordable; budgetLimited = true; }
            }
            if (quantity == 0) break;
            require(!maker.accountId().equals(p.accountId()), SELF_TRADE);
            fills.add(new Fill(maker, quantity));
            remaining -= quantity;
            if (p.type() == OrderType.MARKET && p.side() == Side.BUY) budget -= Math.multiplyExact(maker.price(), quantity);
            if (budgetLimited) break;
        }
        return new Plan(List.copyOf(fills), remaining, budgetLimited);
    }
    private Outcome place(PlaceOrder p, Header h, List<DomainEvent> events) {
        long reserve = validate(p, 0);
        var plan = plan(p);
        require(nextOrderId < Long.MAX_VALUE && nextTradeId <= Long.MAX_VALUE - plan.fills().size(), NUMERIC_OVERFLOW);
        var id = new OrderId(Long.toString(nextOrderId++));
        if (p.timeInForce() == TimeInForce.FOK && plan.remaining() != 0) {
            events.add(new OrderCancelled(h, id, p.quantity(), CancelReason.FOK_KILLED));
            return new Outcome(Status.CANCELLED, id, null, p.quantity(), List.of());
        }
        accounts.change(p.accountId(), reserveAsset(p), -reserve, reserve);
        events.add(new OrderAccepted(h, id, p.accountId()));
        return execute(p, id, reserve, plan, h, events);
    }
    private Outcome execute(PlaceOrder p, OrderId id, long reserve, Plan plan, Header h, List<DomainEvent> events) {
        var instrument = instruments.get(p.symbol());
        var trades = new ArrayList<TradeExecuted>();
        for (var fill : plan.fills()) {
            var maker = fill.maker();
            long q = fill.quantity(), cost = Math.multiplyExact(maker.price(), q);
            boolean takerBuys = p.side() == Side.BUY;
            var buyer = takerBuys ? p.accountId() : maker.accountId();
            var seller = takerBuys ? maker.accountId() : p.accountId();
            long buyerRelease = takerBuys && p.type() == OrderType.LIMIT ? Math.multiplyExact(p.price(), q) : cost;
            accounts.change(buyer, instrument.quoteAsset(), buyerRelease - cost, -buyerRelease);
            accounts.change(seller, instrument.quoteAsset(), cost, 0);
            accounts.change(seller, instrument.baseAsset(), 0, -q);
            accounts.change(buyer, instrument.baseAsset(), q, 0);
            reserve -= takerBuys ? buyerRelease : q;
            long makerReserve = maker.reservation() - (takerBuys ? q : cost);
            if (maker.quantity() == q) book(p.symbol()).remove(maker.orderId());
            else book(p.symbol()).update(new OrderBook.OpenOrder(maker.orderId(), maker.accountId(), maker.clientOrderId(),
                    maker.symbol(), maker.side(), maker.price(), maker.quantity() - q, makerReserve));
            var trade = new TradeExecuted(h, nextTradeId++, p.symbol(), maker.orderId(), id,
                    maker.accountId(), p.accountId(), maker.price(), q);
            trades.add(trade); events.add(trade);
            post(h, "trade:" + trade.tradeId(), List.of(new Ledger.Posting(buyer, instrument.quoteAsset(), -cost),
                    new Ledger.Posting(seller, instrument.quoteAsset(), cost),
                    new Ledger.Posting(seller, instrument.baseAsset(), -q),
                    new Ledger.Posting(buyer, instrument.baseAsset(), q)), events);
        }
        Status status;
        if (plan.remaining() > 0 && p.type() == OrderType.LIMIT && p.timeInForce() == TimeInForce.GTC) {
            var order = new OrderBook.OpenOrder(id, p.accountId(), p.clientOrderId(), p.symbol(), p.side(), p.price(), plan.remaining(), reserve);
            book(p.symbol()).add(order); events.add(new OrderRested(h, order)); status = Status.OPEN;
        } else {
            if (reserve != 0) accounts.change(p.accountId(), reserveAsset(p), reserve, -reserve);
            status = plan.remaining() == 0 ? Status.FILLED : Status.CANCELLED;
            if (plan.remaining() > 0) events.add(new OrderCancelled(h, id, plan.remaining(),
                    p.type() == OrderType.MARKET ? (plan.budgetLimited() ? CancelReason.MARKET_BUDGET : CancelReason.MARKET_NO_LIQUIDITY)
                            : CancelReason.IOC_REMAINDER));
        }
        return new Outcome(status, id, null, plan.remaining(), trades);
    }
    private OrderBook.OpenOrder owned(AccountId account, OrderId id) {
        known(account);
        var order = books.values().stream().map(b -> b.find(id)).flatMap(Optional::stream).findFirst().orElse(null);
        require(order != null, UNKNOWN_ORDER); require(order.accountId().equals(account), NOT_ORDER_OWNER);
        return order;
    }
    private Asset reserveAsset(OrderBook.OpenOrder o) {
        var i = instruments.get(o.symbol()); return o.side() == Side.BUY ? i.quoteAsset() : i.baseAsset();
    }
    private Outcome cancel(CancelOrder c, Header h, List<DomainEvent> events) {
        var o = owned(c.accountId(), c.orderId());
        accounts.change(o.accountId(), reserveAsset(o), o.reservation(), -o.reservation());
        book(o.symbol()).remove(o.orderId());
        events.add(new OrderCancelled(h, o.orderId(), o.quantity(), CancelReason.USER));
        return new Outcome(Status.CANCELLED, o.orderId(), null, o.quantity(), List.of());
    }
    private Outcome replace(ReplaceOrder r, Header h, List<DomainEvent> events) {
        var old = owned(r.accountId(), r.orderId());
        var p = new PlaceOrder(old.accountId(), old.clientOrderId(), old.symbol(), old.side(), OrderType.LIMIT,
                TimeInForce.GTC, r.price(), r.quantity(), null);
        long reserve = validate(p, old.reservation());
        var plan = plan(p);
        require(nextTradeId <= Long.MAX_VALUE - plan.fills().size(), NUMERIC_OVERFLOW);
        boolean retained = r.price() == old.price() && r.quantity() <= old.quantity();
        long delta = reserve - old.reservation();
        accounts.change(old.accountId(), reserveAsset(old), -delta, delta);
        if (retained) {
            book(old.symbol()).update(new OrderBook.OpenOrder(old.orderId(), old.accountId(), old.clientOrderId(), old.symbol(),
                    old.side(), r.price(), r.quantity(), reserve));
            events.add(new OrderReplaced(h, old.orderId(), r.price(), r.quantity(), true));
            return new Outcome(Status.OPEN, old.orderId(), null, r.quantity(), List.of());
        }
        book(old.symbol()).remove(old.orderId());
        events.add(new OrderCancelled(h, old.orderId(), old.quantity(), CancelReason.REPLACED));
        events.add(new OrderReplaced(h, old.orderId(), r.price(), r.quantity(), false));
        return execute(p, old.orderId(), reserve, plan, h, events);
    }
    private void post(Header h, String reference, List<Ledger.Posting> postings, List<DomainEvent> events) {
        var transaction = new Ledger.Transaction(h.sequence(), h.timestamp(), reference, postings);
        ledger.append(transaction); events.add(new LedgerPosted(h, transaction));
    }
    public EngineSnapshot snapshot() {
        return new EngineSnapshot(1, List.copyOf(instruments.values()), accounts.snapshot(),
                books.values().stream().flatMap(b -> b.orders().stream()).toList(), ledger.transactions(),
                nextOrderId, nextTradeId, lastSequence, List.copyOf(idempotency.values()));
    }
    public static MatchingEngine restore(EngineSnapshot snapshot) {
        if (snapshot.version() != 1 || snapshot.nextOrderId() <= 0 || snapshot.nextTradeId() <= 0 || snapshot.lastSequence() < 0)
            throw new IllegalArgumentException("unsupported or invalid snapshot");
        var engine = new MatchingEngine(snapshot.instruments());
        engine.accounts = AccountBook.restore(snapshot.accounts());
        engine.nextOrderId = snapshot.nextOrderId(); engine.nextTradeId = snapshot.nextTradeId(); engine.lastSequence = snapshot.lastSequence();
        for (var order : snapshot.openOrders()) engine.book(order.symbol()).add(order);
        snapshot.ledger().forEach(engine.ledger::append);
        for (var entry : snapshot.idempotency()) {
            var key = new Key(entry.request().accountId(), entry.request().clientOrderId());
            if (engine.idempotency.putIfAbsent(key, entry) != null) throw new IllegalArgumentException("duplicate idempotency key");
        }
        engine.assertInvariants();
        return engine;
    }
    /** Expensive diagnostic for tests/recovery; never required on the matching hot path. */
    public void assertInvariants() {
        record Holding(AccountId account, Asset asset) {}
        var reservations = new HashMap<Holding, Long>();
        var ids = new HashSet<OrderId>();
        for (var b : books.values()) {
            if (b.isCrossed()) throw new IllegalStateException("crossed book");
            for (var o : b.orders()) {
                var i = instruments.get(o.symbol());
                if (!ids.add(o.orderId()) || Long.parseLong(o.orderId().value()) >= nextOrderId
                        || o.quantity() <= 0 || o.quantity() % i.lotSize().units() != 0
                        || o.price() <= 0 || o.price() % i.tickSize().units() != 0
                        || o.reservation() != (o.side() == Side.BUY ? Math.multiplyExact(o.price(), o.quantity()) : o.quantity()))
                    throw new IllegalStateException("invalid open order");
                reservations.merge(new Holding(o.accountId(), reserveAsset(o)), o.reservation(), Math::addExact);
            }
        }
        for (var a : accounts.snapshot()) {
            if (a.accountId().equals(Ledger.EXTERNAL)) throw new IllegalStateException("external customer");
            for (var balance : a.balances()) {
                var key = new Holding(a.accountId(), balance.asset());
                if (!scales.containsKey(balance.asset()) || balance.balance().reserved() != reservations.getOrDefault(key, 0L))
                    throw new IllegalStateException("reservation mismatch");
                reservations.remove(key);
            }
        }
        if (!reservations.isEmpty()) throw new IllegalStateException("reservation for unknown holding");
        accounts.totals();
        ledger.assertConservation(accounts.snapshot());
    }
}
