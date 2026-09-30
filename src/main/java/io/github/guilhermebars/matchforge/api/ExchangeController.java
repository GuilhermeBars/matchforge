package io.github.guilhermebars.matchforge.api;

import static io.github.guilhermebars.matchforge.api.ApiDtos.Account;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Book;
import static io.github.guilhermebars.matchforge.api.ApiDtos.CreateAccount;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Entry;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Fill;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Funds;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Market;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Order;
import static io.github.guilhermebars.matchforge.api.ApiDtos.OrderStatus;
import static io.github.guilhermebars.matchforge.api.ApiDtos.PlaceOrder;
import static io.github.guilhermebars.matchforge.api.ApiDtos.ReplaceOrder;
import static io.github.guilhermebars.matchforge.api.ApiDtos.Snapshot;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.service.EngineService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExchangeController {
    private final EngineService engine;
    private final ApiMapper mapper;

    public ExchangeController(EngineService engine, ApiMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    private CommandResult execute(Command command) {
        var result = engine.submit(command).join();
        check(result, command instanceof Command.PlaceOrder p ? mapper.order(p, result.outcome()) : null);
        return result;
    }

    private void check(CommandResult r, Object order) {
        var reason = r.outcome().reason();
        if (reason == null) return;
        int status =
                switch (reason) {
                    case UNKNOWN_ACCOUNT, UNKNOWN_ORDER, UNKNOWN_SYMBOL, UNKNOWN_ASSET -> 404;
                    case DUPLICATE_CLIENT_ORDER_ID, ACCOUNT_EXISTS -> 409;
                    case INSUFFICIENT_FUNDS, SELF_TRADE, NOT_ORDER_OWNER -> 422;
                    default -> 400;
                };
        throw new ApiException(status, reason.name(), order, r.duplicate());
    }

    @PostMapping("/accounts")
    @Tag(name = "Accounts")
    public ResponseEntity<Account> create(@Valid @RequestBody(required = false) CreateAccount body) {
        String id = body == null || body.id() == null ? UUID.randomUUID().toString() : body.id();
        execute(new Command.CreateAccount(new AccountId(id)));
        return ResponseEntity.status(201).body(mapper.account(id));
    }

    @GetMapping("/accounts/{id}")
    @Tag(name = "Accounts")
    public Account account(@PathVariable String id) {
        return mapper.account(id);
    }

    @PostMapping("/accounts/{id}/deposits")
    @Tag(name = "Accounts")
    public Account deposit(@PathVariable String id, @Valid @RequestBody Funds body) {
        execute(new Command.Deposit(
                new AccountId(id),
                new Asset(body.asset()),
                ApiMapper.parse(body.amount(), mapper.scale(body.asset()))));
        return mapper.account(id);
    }

    @PostMapping("/accounts/{id}/withdrawals")
    @Tag(name = "Accounts")
    public Account withdraw(@PathVariable String id, @Valid @RequestBody Funds body) {
        execute(new Command.Withdraw(
                new AccountId(id),
                new Asset(body.asset()),
                ApiMapper.parse(body.amount(), mapper.scale(body.asset()))));
        return mapper.account(id);
    }

    @PostMapping("/orders")
    @Tag(name = "Orders")
    public ResponseEntity<Order> place(@Valid @RequestBody PlaceOrder body) {
        var i = mapper.instrument(body.symbol());
        if (body.type() == OrderType.LIMIT && body.price() == null
                || body.type() == OrderType.MARKET && body.price() != null)
            throw new ApiException(400, "INVALID_ORDER");
        var command = new Command.PlaceOrder(
                new AccountId(body.accountId()),
                new ClientOrderId(body.clientOrderId()),
                i.symbol(),
                body.side(),
                body.type(),
                body.timeInForce(),
                body.price() == null ? 0 : ApiMapper.parse(body.price(), i.priceScale()),
                ApiMapper.parse(body.quantity(), i.quantityScale()),
                body.quoteBudget() == null
                        ? null
                        : ApiMapper.parse(
                                body.quoteBudget(), mapper.scale(i.quoteAsset().value())));
        var result = execute(command);
        return ResponseEntity.status(result.duplicate() ? 200 : 201)
                .header("Idempotent-Replay", Boolean.toString(result.duplicate()))
                .body(mapper.order(command, result.outcome()));
    }

    @GetMapping("/orders/{id}")
    @Tag(name = "Orders")
    public Order order(@PathVariable String id) {
        return mapper.order(id);
    }

    @DeleteMapping("/orders/{id}")
    @Tag(name = "Orders")
    public Order cancel(@PathVariable String id) {
        var request = mapper.request(new OrderId(id), engine.readModel());
        execute(new Command.CancelOrder(request.accountId(), new OrderId(id)));
        return mapper.order(id);
    }

    @PutMapping("/orders/{id}")
    @Tag(name = "Orders")
    public Order replace(@PathVariable String id, @Valid @RequestBody ReplaceOrder body) {
        if (body.price() == null && body.quantity() == null) throw new ApiException(400, "VALIDATION_ERROR");
        var current = engine.readModel().engineState().openOrders().stream()
                .filter(o -> o.orderId().equals(new OrderId(id)))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "UNKNOWN_ORDER"));
        var i = mapper.instrument(current.symbol().value());
        execute(new Command.ReplaceOrder(
                current.accountId(),
                current.orderId(),
                body.price() == null ? current.price() : ApiMapper.parse(body.price(), i.priceScale()),
                body.quantity() == null ? current.quantity() : ApiMapper.parse(body.quantity(), i.quantityScale())));
        return mapper.order(id);
    }

    @GetMapping("/accounts/{id}/orders")
    @Tag(name = "Orders")
    public List<Order> orders(@PathVariable String id, @RequestParam(required = false) String status) {
        mapper.account(id);
        if (status != null
                && !status.equals("OPEN")
                && Arrays.stream(OrderStatus.values()).noneMatch(s -> s.name().equals(status)))
            throw new ApiException(400, "VALIDATION_ERROR");
        var view = engine.readModel();
        return view.engineState().idempotency().stream()
                .filter(e -> e.request().accountId().value().equals(id))
                .map(e -> mapper.order(
                        e.request(),
                        e.outcome().orderId() == null
                                ? e.outcome()
                                : view.orders().getOrDefault(e.outcome().orderId(), e.outcome())))
                .filter(o -> status == null
                        || status.equals(o.status().name())
                        || status.equals("OPEN")
                                && (o.status() == OrderStatus.NEW || o.status() == OrderStatus.PARTIALLY_FILLED))
                .toList();
    }

    @GetMapping("/markets")
    @Tag(name = "Markets")
    public List<Market> markets() {
        return engine.readModel().engineState().instruments().stream()
                .map(i -> new Market(
                        i.symbol().value(),
                        i.baseAsset().value(),
                        i.quoteAsset().value(),
                        ApiMapper.decimal(i.tickSize().units(), i.priceScale()),
                        ApiMapper.decimal(i.lotSize().units(), i.quantityScale()),
                        i.priceScale(),
                        i.quantityScale()))
                .toList();
    }

    @GetMapping("/markets/{symbol}/book")
    @Tag(name = "Markets")
    public Book book(@PathVariable String symbol, @RequestParam(defaultValue = "10") @Min(1) @Max(100) int depth) {
        return mapper.book(symbol, depth);
    }

    @GetMapping("/markets/{symbol}/trades")
    @Tag(name = "Markets")
    public List<Fill> trades(
            @PathVariable String symbol, @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        mapper.instrument(symbol);
        return engine.readModel().recentTrades().reversed().stream()
                .filter(t -> t.symbol().value().equals(symbol))
                .limit(limit)
                .map(mapper::fill)
                .toList();
    }

    @GetMapping("/ledger/accounts/{id}/entries")
    @Tag(name = "Ledger")
    public List<Entry> ledger(@PathVariable String id) {
        mapper.account(id);
        return engine.readModel().engineState().ledger().stream()
                .flatMap(t -> t.postings().stream()
                        .filter(p -> p.accountId().value().equals(id))
                        .map(p -> new Entry(
                                t.sequence(),
                                t.timestamp(),
                                t.reference(),
                                p.asset().value(),
                                ApiMapper.decimal(
                                        p.amount(), mapper.scale(p.asset().value())))))
                .toList();
    }

    @PostMapping("/admin/snapshots")
    @Tag(name = "Admin")
    public Snapshot snapshot() {
        return new Snapshot(engine.snapshot().join().seq());
    }
}
