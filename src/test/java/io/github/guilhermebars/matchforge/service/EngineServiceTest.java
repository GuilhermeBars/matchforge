package io.github.guilhermebars.matchforge.service;

import static io.github.guilhermebars.matchforge.service.ServiceFixture.A;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.B;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.USD;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.fund;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.order;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.events.EventPublisher;
import io.github.guilhermebars.matchforge.events.InProcessEventPublisher;
import io.github.guilhermebars.matchforge.journal.InMemoryJournal;
import io.github.guilhermebars.matchforge.journal.InMemorySnapshotStore;
import io.github.guilhermebars.matchforge.journal.Journal;
import io.github.guilhermebars.matchforge.journal.JournalEntry;
import io.github.guilhermebars.matchforge.journal.SnapshotStore;
import io.github.guilhermebars.matchforge.journal.StoredSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EngineServiceTest {
    @Test
    void restartWithoutSnapshotRestoresAllReadModelsAndOriginalOutcomes() {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        ExchangeReadModel before;
        CommandResult original;
        var request = order(A, "maker", Side.SELL, 10, 7);
        try (var service = service(journal, snapshots, 1000)) {
            fund(service);
            original = service.submit(request).join();
            service.submit(order(B, "taker", Side.BUY, 10, 7)).join();
            before = service.readModel();
            assertThat(before.orders().get(original.outcome().orderId()).status())
                    .isEqualTo(CommandResult.Status.FILLED);
        }
        try (var recovered = service(journal, snapshots, 1000)) {
            assertThat(recovered.readModel()).isEqualTo(before);
            var duplicate = recovered.submit(request).join();
            assertThat(duplicate.duplicate()).isTrue();
            assertThat(duplicate.outcome()).isEqualTo(original.outcome());
            assertThat(duplicate.events()).isEmpty();
            assertThat(journal.lastSeq()).isEqualTo(before.sequence() + 1);
            assertThat(recovered
                            .submit(order(A, "maker", Side.SELL, 11, 7))
                            .join()
                            .outcome()
                            .status())
                    .isEqualTo(CommandResult.Status.CONFLICT);
        }
    }

    @Test
    void snapshotTailReplayPreservesClosedOrdersTradesAndIdempotency() {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        ExchangeReadModel expected;
        var request = order(A, "seller", Side.SELL, 10, 8);
        CommandResult original;
        try (var service = service(journal, snapshots, 7)) {
            fund(service);
            original = service.submit(request).join();
            assertThat(snapshots.latest().orElseThrow().seq()).isEqualTo(7);
            service.submit(order(B, "buyer", Side.BUY, 10, 3)).join();
            service.snapshot().join();
            service.submit(new Command.CancelOrder(A, original.outcome().orderId()))
                    .join();
            expected = service.readModel();
        }
        try (var recovered = service(journal, snapshots, 1000)) {
            assertThat(recovered.readModel()).isEqualTo(expected);
            assertThat(recovered.submit(request).join().outcome()).isEqualTo(original.outcome());
        }
    }

    @Test
    void rejectedPlacementRemainsIdempotentAfterFundingAndRestart() {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        var request = order(A, "reject", Side.BUY, 20, 1);
        CommandResult original;
        try (var service = service(journal, snapshots, 1)) {
            service.submit(new Command.CreateAccount(A)).join();
            original = service.submit(request).join();
            service.submit(new Command.Deposit(A, USD, 500)).join();
        }
        try (var recovered = service(journal, snapshots, 1)) {
            assertThat(recovered.submit(request).join().outcome()).isEqualTo(original.outcome());
            assertThat(recovered.readModel().engineState().openOrders()).isEmpty();
        }
    }

    @Test
    void boundedQueueRejectsWith503AndShutdownDrainsAcceptedWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        EventPublisher blocking = events -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        var journal = new InMemoryJournal();
        var service = service(journal, new InMemorySnapshotStore(), 100, 1, blocking, new SimpleMeterRegistry());
        try {
            var first = service.submit(new Command.CreateAccount(A));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = service.submit(new Command.CreateAccount(B));
            assertThatThrownBy(
                            () -> service.submit(new Command.Deposit(A, USD, 1)).join())
                    .hasCauseInstanceOf(EngineService.UnavailableException.class);
            assertThat(EngineService.UnavailableException.class
                            .getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class)
                            .value()
                            .value())
                    .isEqualTo(503);
            release.countDown();
            service.close();
            assertThat(first.join().outcome().status()).isEqualTo(CommandResult.Status.OK);
            assertThat(second.join().outcome().status()).isEqualTo(CommandResult.Status.OK);
            assertThat(journal.lastSeq()).isEqualTo(2);
            assertThatThrownBy(
                            () -> service.submit(new Command.CreateAccount(A)).join())
                    .hasCauseInstanceOf(EngineService.UnavailableException.class);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void committedCommandRecoveredAfterPublisherFailureWithoutRepublishing() {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        try (var service = service(
                journal,
                snapshots,
                100,
                4,
                events -> {
                    throw new IllegalStateException("publisher down");
                },
                new SimpleMeterRegistry())) {
            assertThatThrownBy(
                            () -> service.submit(new Command.CreateAccount(A)).join())
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(service.available()).isFalse();
            assertThat(journal.lastSeq()).isEqualTo(1);
            assertThatThrownBy(
                            () -> service.submit(new Command.CreateAccount(B)).join())
                    .hasCauseInstanceOf(EngineService.UnavailableException.class);
        }
        var batches = new ArrayList<List<DomainEvent>>();
        try (var recovered = service(journal, snapshots, 100, 4, batches::add, new SimpleMeterRegistry())) {
            assertThat(recovered.readModel().engineState().accounts()).hasSize(1);
            assertThat(batches).isEmpty();
        }
    }

    @Test
    void appendFailureHasNoEngineEffectsAndFencesAmbiguousCommit() {
        Journal journal = new Journal() {
            public long append(JournalEntry e) {
                throw new IllegalStateException("disk unavailable");
            }

            public List<JournalEntry> readAfter(long seq) {
                return List.of();
            }

            public long lastSequence() {
                return 0;
            }
        };
        try (var service = service(journal, new InMemorySnapshotStore(), 1)) {
            var before = service.readModel();
            assertThatThrownBy(
                            () -> service.submit(new Command.CreateAccount(A)).join())
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(service.readModel()).isEqualTo(before);
            assertThat(service.available()).isFalse();
        }
    }

    @Test
    void corruptSnapshotFailsRecovery() {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        StoredSnapshot saved;
        try (var service = service(journal, snapshots, 1)) {
            fund(service);
            saved = service.snapshot().join();
        }
        SnapshotStore corrupt = new SnapshotStore() {
            public void save(StoredSnapshot s) {}

            public Optional<StoredSnapshot> latest() {
                return Optional.of(new StoredSnapshot(saved.seq(), "bad", saved.state()));
            }
        };
        assertThatThrownBy(() -> service(journal, corrupt, 1)).hasMessageContaining("checksum");
    }

    @Test
    void metricsAndViewsReflectCommandsAndDoNotMutatePreviouslyPublishedView() {
        var metrics = new SimpleMeterRegistry();
        var journal = new InMemoryJournal();
        try (var service =
                service(journal, new InMemorySnapshotStore(), 100, 8, new InProcessEventPublisher(), metrics)) {
            fund(service);
            var old = service.readModel();
            service.submit(order(A, "ask", Side.SELL, 5, 2)).join();
            service.submit(order(B, "buy", Side.BUY, 5, 1)).join();
            service.submit(order(B, "bad", Side.BUY, 0, 1)).join();
            assertThat(old.engineState().openOrders()).isEmpty();
            assertThat(metrics.get("matchforge.orders.accepted").counter().count())
                    .isEqualTo(2);
            assertThat(metrics.get("matchforge.orders.rejected").counter().count())
                    .isEqualTo(1);
            assertThat(metrics.get("matchforge.trades").counter().count()).isEqualTo(1);
            assertThat(metrics.get("matchforge.commands.processed").timer().count())
                    .isEqualTo(9);
            assertThat(metrics.get("matchforge.journal.append").timer().count()).isEqualTo(9);
            assertThat(metrics.get("matchforge.journal.seq").gauge().value()).isEqualTo(9);
            assertThat(metrics.get("matchforge.book.depth")
                            .tag("side", "SELL")
                            .gauge()
                            .value())
                    .isEqualTo(1);
        }
    }
}
