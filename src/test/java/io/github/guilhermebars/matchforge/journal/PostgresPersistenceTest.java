package io.github.guilhermebars.matchforge.journal;

import static io.github.guilhermebars.matchforge.service.ServiceFixture.A;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.B;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.fund;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.order;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.service.ExchangeReadModel;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private final PersistenceCodec codec = new PersistenceCodec();

    @BeforeEach
    void prepare() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcClient.create(dataSource).sql("TRUNCATE snapshot, journal_entry").update();
    }

    @Test
    void appendReadInclusiveExclusiveAndContiguousContract() {
        try (var journal = new PostgresJournal(dataSource)) {
            var command = new Command.CreateAccount(A);
            var entry = new JournalEntry(
                    1, codec.type(command), codec.encode(command), Instant.parse("2026-01-01T00:00:00.123456Z"));
            assertThat(journal.append(entry)).isEqualTo(1);
            var read = journal.readFrom(1).getFirst();
            assertThat(codec.command(read)).isEqualTo(command);
            assertThat(read.createdAt()).isEqualTo(entry.createdAt());
            assertThat(journal.readAfter(1)).isEmpty();
            assertThat(journal.lastSeq()).isEqualTo(1);
            assertThatThrownBy(
                            () -> journal.append(new JournalEntry(3, entry.type(), entry.payload(), entry.createdAt())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(journal.lastSeq()).isEqualTo(1);
        }
    }

    @Test
    void exclusiveWriterLeaseReleasedOnClose() {
        try (var first = new PostgresJournal(dataSource)) {
            assertThatThrownBy(() -> new PostgresJournal(dataSource)).hasMessageContaining("lease");
        }
        try (var next = new PostgresJournal(dataSource)) {
            assertThat(next.lastSeq()).isZero();
        }
    }

    @Test
    void durableSnapshotTailRecoveryAndIdempotency() {
        ExchangeReadModel expected;
        CommandResult original;
        var request = order(A, "sell", Side.SELL, 10, 5);
        try (var journal = new PostgresJournal(dataSource)) {
            var snapshots = new PostgresSnapshotStore(JdbcClient.create(dataSource), codec);
            try (var service = service(journal, snapshots, 7)) {
                fund(service);
                original = service.submit(request).join();
                service.submit(order(B, "buy", Side.BUY, 10, 3)).join();
                expected = service.readModel();
                assertThat(snapshots.latest().orElseThrow().seq()).isEqualTo(7);
            }
        }
        try (var journal = new PostgresJournal(dataSource)) {
            var snapshots = new PostgresSnapshotStore(JdbcClient.create(dataSource), codec);
            try (var recovered = service(journal, snapshots, 1000)) {
                assertThat(recovered.readModel()).isEqualTo(expected);
                assertThat(recovered.submit(request).join().outcome()).isEqualTo(original.outcome());
                assertThat(recovered.readModel().engineState().openOrders()).hasSize(1);
            }
            try (var full = service(journal, new InMemorySnapshotStore(), 1000)) {
                assertThat(full.readModel().engineState().accounts())
                        .isEqualTo(expected.engineState().accounts());
            }
        }
    }

    @Test
    void storedChecksumDetectsTampering() {
        try (var journal = new PostgresJournal(dataSource)) {
            var jdbc = JdbcClient.create(dataSource);
            var snapshots = new PostgresSnapshotStore(jdbc, codec);
            try (var service = service(journal, snapshots, 1)) {
                fund(service);
            }
            jdbc.sql("UPDATE snapshot SET payload = jsonb_set(payload, '{checksum}', '\"corrupted\"'::jsonb)")
                    .update();
            assertThatThrownBy(snapshots::latest).hasMessageContaining("checksum");
        }
    }
}
