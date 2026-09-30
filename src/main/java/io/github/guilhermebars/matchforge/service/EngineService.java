package io.github.guilhermebars.matchforge.service;

import io.github.guilhermebars.matchforge.domain.InstrumentConfig;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.engine.CommandEnvelope;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.engine.MatchingEngine;
import io.github.guilhermebars.matchforge.engine.OrderBook;
import io.github.guilhermebars.matchforge.events.EventPublisher;
import io.github.guilhermebars.matchforge.journal.Journal;
import io.github.guilhermebars.matchforge.journal.JournalEntry;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import io.github.guilhermebars.matchforge.journal.SnapshotStore;
import io.github.guilhermebars.matchforge.journal.StoredSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Owns the engine. Recovery finishes before admission; every mutation runs on one bounded writer. */
public final class EngineService implements AutoCloseable {
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private final Journal journal;
    private final SnapshotStore snapshots;
    private final PersistenceCodec codec;
    private final EventPublisher publisher;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final int snapshotInterval;
    private final ThreadPoolExecutor writer;
    private final MatchingEngine engine;
    private final LinkedHashMap<OrderId, CommandResult.Outcome> orders = new LinkedHashMap<>();
    private final ArrayDeque<DomainEvent.TradeExecuted> trades = new ArrayDeque<>();
    private volatile ExchangeReadModel view;
    private volatile Throwable failure;
    private volatile long journalSequence;
    private volatile Thread writerThread;

    public EngineService(
            List<InstrumentConfig> instruments,
            Journal journal,
            SnapshotStore snapshots,
            PersistenceCodec codec,
            EventPublisher publisher,
            MeterRegistry metrics,
            int snapshotInterval,
            int queueCapacity,
            Clock clock) {
        if (snapshotInterval < 1 || queueCapacity < 1)
            throw new IllegalArgumentException("positive interval/capacity required");
        this.journal = journal;
        this.snapshots = snapshots;
        this.codec = codec;
        this.publisher = publisher;
        this.metrics = metrics;
        this.snapshotInterval = snapshotInterval;
        this.clock = clock;
        var saved = snapshots.latest();
        if (saved.isPresent()) {
            var snapshot = saved.get();
            snapshot.verify(codec);
            if (!snapshot.state().engine().instruments().equals(instruments) || snapshot.seq() > journal.lastSeq())
                throw new IllegalStateException("Snapshot configuration or journal boundary mismatch");
            engine = MatchingEngine.restore(snapshot.state().engine());
            snapshot.state().orders().forEach(o -> orders.put(o.orderId(), o.outcome()));
            trades.addAll(snapshot.state().recentTrades());
        } else engine = new MatchingEngine(instruments);
        while (true) {
            var batch = journal.readBatchAfter(engine.lastSequence(), 1000);
            if (batch.isEmpty()) break;
            for (var entry : batch) {
                var command = codec.command(entry);
                project(engine.process(new CommandEnvelope(entry.seq(), entry.createdAt(), command)));
            }
        }
        journalSequence = journal.lastSeq();
        if (engine.lastSequence() != journal.lastSeq()) throw new IllegalStateException("Incomplete journal recovery");
        engine.assertInvariants();
        publishView();
        writer = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    var thread = new Thread(r, "matchforge-engine");
                    writerThread = thread;
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("matchforge.journal.seq", this, s -> s.journalSequence).register(metrics);
        for (var instrument : instruments) {
            for (var side : Side.values()) {
                Gauge.builder("matchforge.book.depth", this, s -> {
                            var depth = s.view.books().get(instrument.symbol());
                            return side == Side.BUY
                                    ? depth.bids().size()
                                    : depth.asks().size();
                        })
                        .tag("symbol", instrument.symbol().value())
                        .tag("side", side.name())
                        .register(metrics);
            }
        }
    }

    public ExchangeReadModel readModel() {
        return view;
    }

    public boolean available() {
        return failure == null && !writer.isShutdown();
    }

    public CompletableFuture<CommandResult> submit(Command command) {
        Objects.requireNonNull(command);
        return enqueue(() -> {
            var sample = io.micrometer.core.instrument.Timer.start(metrics);
            try {
                long seq = Math.addExact(engine.lastSequence(), 1);
                var envelope = new CommandEnvelope(seq, clock.instant().truncatedTo(ChronoUnit.MICROS), command);
                var entry = new JournalEntry(seq, codec.type(command), codec.encode(command), envelope.timestamp());
                var append = io.micrometer.core.instrument.Timer.start(metrics);
                try {
                    if (journal.append(entry) != seq)
                        throw new IllegalStateException("Journal returned wrong sequence");
                    journalSequence = seq;
                } finally {
                    append.stop(metrics.timer("matchforge.journal.append"));
                }
                var result = engine.process(envelope);
                project(result);
                publishView();
                recordMetrics(command, result);
                // Failure after WAL commit fences admission. Recovery never republishes historical events.
                publisher.publish(envelope, result.events());
                if (seq % snapshotInterval == 0) saveSnapshot();
                return result;
            } finally {
                sample.stop(metrics.timer("matchforge.commands.processed"));
            }
        });
    }

    public CompletableFuture<StoredSnapshot> snapshot() {
        return enqueue(this::saveSnapshot);
    }

    private StoredSnapshot saveSnapshot() {
        var state = new StoredSnapshot.State(
                1,
                engine.snapshot(),
                orders.entrySet().stream()
                        .map(e -> new StoredSnapshot.OrderState(e.getKey(), e.getValue()))
                        .toList(),
                List.copyOf(trades));
        var snapshot = StoredSnapshot.create(state, codec);
        snapshots.save(snapshot);
        return snapshot;
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> work) {
        var future = new CompletableFuture<T>();
        if (failure != null)
            return CompletableFuture.failedFuture(new UnavailableException("Engine requires recovery"));
        try {
            writer.execute(() -> {
                if (failure != null) {
                    future.completeExceptionally(new UnavailableException("Engine requires recovery"));
                    return;
                }
                try {
                    future.complete(work.get());
                } catch (Throwable e) {
                    failure = e;
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new UnavailableException("Engine queue full or service stopped"));
        }
        return future;
    }

    private void project(CommandResult result) {
        if (result.duplicate() || result.outcome().status() == CommandResult.Status.CONFLICT) return;
        for (var event : result.events()) {
            if (event instanceof DomainEvent.TradeExecuted trade) {
                trades.addLast(trade);
                if (trades.size() > 1000) trades.removeFirst();
                var old = orders.get(trade.makerOrderId());
                if (old != null) {
                    long remaining = old.remainingQuantity() - trade.quantity();
                    var fills = new ArrayList<>(old.trades());
                    fills.add(trade);
                    orders.put(
                            trade.makerOrderId(),
                            new CommandResult.Outcome(
                                    remaining == 0 ? CommandResult.Status.FILLED : CommandResult.Status.OPEN,
                                    trade.makerOrderId(),
                                    null,
                                    remaining,
                                    fills));
                }
            }
        }
        var outcome = result.outcome();
        if (outcome.orderId() != null && outcome.status() != CommandResult.Status.REJECTED) {
            var old = orders.get(outcome.orderId());
            if (old != null && !old.trades().isEmpty()) {
                var fills = new ArrayList<>(old.trades());
                fills.addAll(outcome.trades());
                outcome = new CommandResult.Outcome(
                        outcome.status(),
                        outcome.orderId(),
                        outcome.reason(),
                        outcome.remainingQuantity(),
                        fills,
                        outcome.cancelReason());
            }
            orders.put(outcome.orderId(), outcome);
        }
    }

    private void publishView() {
        var books = new LinkedHashMap<Symbol, OrderBook.Depth>();
        var state = engine.snapshot();
        state.instruments()
                .forEach(i -> books.put(i.symbol(), engine.book(i.symbol()).depth(Integer.MAX_VALUE)));
        view = new ExchangeReadModel(state, books, orders, List.copyOf(trades));
    }

    private void recordMetrics(Command command, CommandResult result) {
        for (var event : result.events()) {
            if (event instanceof DomainEvent.OrderAccepted)
                metrics.counter("matchforge.orders.accepted", "reason", "accepted")
                        .increment();
            if (event instanceof DomainEvent.TradeExecuted)
                metrics.counter("matchforge.trades").increment();
            if (event instanceof DomainEvent.OrderCancelled)
                metrics.counter("matchforge.cancels").increment();
        }
        if ((command instanceof Command.PlaceOrder || command instanceof Command.ReplaceOrder)
                && !result.duplicate()
                && result.outcome().reason() != null)
            metrics.counter(
                            "matchforge.orders.rejected",
                            "reason",
                            result.outcome().reason().name())
                    .increment();
    }

    @Override
    public void close() {
        if (Thread.currentThread() == writerThread)
            throw new IllegalStateException("Cannot close from engine listener");
        writer.shutdown();
        boolean interrupted = false;
        // Accepted commands are never abandoned; adapters must have bounded I/O timeouts.
        while (!writer.isTerminated()) {
            try {
                writer.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
