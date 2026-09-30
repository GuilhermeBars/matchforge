package io.github.guilhermebars.matchforge.service;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.*;
import io.github.guilhermebars.matchforge.journal.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import java.util.List;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.*;
import static org.assertj.core.api.Assertions.*;

class JournalReplayProperties {
    @Property(tries = 50)
    void randomCommandsRecoverIdenticallyWithAndWithoutSnapshot(
            @ForAll @Size(min = 25, max = 75) List<@IntRange(min = 0, max = 999) Integer> actions) {
        var journal = new InMemoryJournal(); var snapshots = new InMemorySnapshotStore(); ExchangeReadModel expected;
        try (var service = service(journal, snapshots, 17)) {
            fund(service);
            for (int i = 0; i < actions.size(); i++) {
                int n = actions.get(i); var account = n % 2 == 0 ? A : B;
                Command command = switch (n % 6) {
                    case 0 -> new Command.Deposit(account, USD, n + 1);
                    case 1 -> new Command.Withdraw(account, BTC, n % 20 + 1);
                    case 2 -> new Command.CancelOrder(account, new OrderId(Integer.toString(n % 15 + 1)));
                    case 3 -> new Command.ReplaceOrder(account, new OrderId(Integer.toString(n % 15 + 1)), n % 12 + 1, n % 5 + 1);
                    default -> order(account, "c" + n % 20, n % 2 == 0 ? Side.BUY : Side.SELL, n % 12 + 1, n % 5 + 1);
                };
                service.submit(command).join();
            }
            expected = service.readModel();
        }
        try (var tail = service(journal, snapshots, 1000);
             var full = service(journal, new InMemorySnapshotStore(), 1000)) {
            assertThat(tail.readModel()).isEqualTo(expected); assertThat(full.readModel()).isEqualTo(expected);
            var codec = new PersistenceCodec();
            assertThat(codec.hash(tail.readModel().engineState())).isEqualTo(codec.hash(full.readModel().engineState()));
        }
    }
}
