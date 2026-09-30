package io.github.guilhermebars.matchforge.journal;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Instant;
import java.util.stream.Stream;
import static io.github.guilhermebars.matchforge.service.ServiceFixture.*;
import static org.assertj.core.api.Assertions.*;

class PersistenceCodecTest {
    @Test void additiveCancelReasonPreservesLegacyEncodingAndNewReason() {
        var codec = new PersistenceCodec();
        String legacy = "{\"status\":\"CANCELLED\",\"orderId\":{\"value\":\"1\"},\"reason\":null,\"remainingQuantity\":2,\"trades\":[]}";
        var old = codec.decode(legacy, CommandResult.Outcome.class);
        assertThat(codec.encode(old)).isEqualTo(legacy);
        var current = new CommandResult.Outcome(old.status(), old.orderId(), null, 2, java.util.List.of(), DomainEvent.CancelReason.USER);
        assertThat(codec.decode(codec.encode(current), CommandResult.Outcome.class)).isEqualTo(current);
    }
    static Stream<Command> commands() {
        return Stream.of(new Command.CreateAccount(A), new Command.Deposit(A, USD, 5), new Command.Withdraw(A, BTC, 3),
                order(A, "one", Side.BUY, 42, 2), new Command.CancelOrder(A, new OrderId("1")),
                new Command.ReplaceOrder(A, new OrderId("1"), 45, 3));
    }
    @ParameterizedTest @MethodSource("commands") void stablePolymorphicRoundTrip(Command command) {
        var codec = new PersistenceCodec();
        var json = codec.encode(command);
        assertThat(json).contains("\"commandType\":\"" + codec.type(command) + "\"").doesNotContain("io.github");
        assertThat(codec.command(new JournalEntry(1, codec.type(command), json, Instant.EPOCH))).isEqualTo(command);
    }
    @Test void rejectsUnknownTypeAndMismatchedEnvelope() {
        var codec = new PersistenceCodec();
        assertThatThrownBy(() -> codec.decode("{\"commandType\":\"arbitrary.class\"}", Command.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.command(new JournalEntry(1, "withdraw.v1", codec.encode(new Command.CreateAccount(A)), Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
