package io.github.guilhermebars.matchforge.service;

import static io.github.guilhermebars.matchforge.service.ServiceFixture.A;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.B;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.BTC;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.USD;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.fund;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.order;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.service;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.journal.InMemoryJournal;
import io.github.guilhermebars.matchforge.journal.InMemorySnapshotStore;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class JournalReplayProperties {
    @Property(tries = 50)
    void randomCommandsRecoverIdenticallyWithAndWithoutSnapshot(
            @ForAll @Size(min = 25, max = 75) List<@IntRange(min = 0, max = 999) Integer> actions) {
        var journal = new InMemoryJournal();
        var snapshots = new InMemorySnapshotStore();
        ExchangeReadModel expected;
        try (var service = service(journal, snapshots, 17)) {
            fund(service);
            for (int i = 0; i < actions.size(); i++) {
                int n = actions.get(i);
                var account = n % 2 == 0 ? A : B;
                Command command =
                        switch (n % 6) {
                            case 0 -> new Command.Deposit(account, USD, n + 1);
                            case 1 -> new Command.Withdraw(account, BTC, n % 20 + 1);
                            case 2 -> new Command.CancelOrder(account, new OrderId(Integer.toString(n % 15 + 1)));
                            case 3 ->
                                new Command.ReplaceOrder(
                                        account, new OrderId(Integer.toString(n % 15 + 1)), n % 12 + 1, n % 5 + 1);
                            default ->
                                order(account, "c" + n % 20, n % 2 == 0 ? Side.BUY : Side.SELL, n % 12 + 1, n % 5 + 1);
                        };
                service.submit(command).join();
            }
            expected = service.readModel();
        }
        try (var tail = service(journal, snapshots, 1000);
                var full = service(journal, new InMemorySnapshotStore(), 1000)) {
            assertThat(tail.readModel()).isEqualTo(expected);
            assertThat(full.readModel()).isEqualTo(expected);
            var codec = new PersistenceCodec();
            assertThat(codec.hash(tail.readModel().engineState()))
                    .isEqualTo(codec.hash(full.readModel().engineState()));
        }
    }
}
