package io.github.guilhermebars.matchforge.journal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guilhermebars.matchforge.engine.Command;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Dedicated persistence mapper: stable explicit wire names, never Java class names. */
public final class PersistenceCodec {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "commandType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Command.CreateAccount.class, name = "create-account.v1"),
        @JsonSubTypes.Type(value = Command.Deposit.class, name = "deposit.v1"),
        @JsonSubTypes.Type(value = Command.Withdraw.class, name = "withdraw.v1"),
        @JsonSubTypes.Type(value = Command.PlaceOrder.class, name = "place-order.v1"),
        @JsonSubTypes.Type(value = Command.CancelOrder.class, name = "cancel-order.v1"),
        @JsonSubTypes.Type(value = Command.ReplaceOrder.class, name = "replace-order.v1")
    })
    private interface CommandTypes {}
    // Preserve legacy snapshot checksums when the additive cancellation reason is absent.
    private interface OutcomeFields {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.github.guilhermebars.matchforge.engine.DomainEvent.CancelReason cancelReason();
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addMixIn(Command.class, CommandTypes.class)
            .addMixIn(io.github.guilhermebars.matchforge.engine.CommandResult.Outcome.class, OutcomeFields.class);

    public String type(Command command) {
        return switch (command) {
            case Command.CreateAccount c -> "create-account.v1";
            case Command.Deposit c -> "deposit.v1";
            case Command.Withdraw c -> "withdraw.v1";
            case Command.PlaceOrder c -> "place-order.v1";
            case Command.CancelOrder c -> "cancel-order.v1";
            case Command.ReplaceOrder c -> "replace-order.v1";
        };
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode persisted value", e);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid persisted value", e);
        }
    }

    public Command command(JournalEntry entry) {
        var command = decode(entry.payload(), Command.class);
        if (!type(command).equals(entry.type())) throw new IllegalArgumentException("Command type mismatch");
        return command;
    }

    public String hash(Object state) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(encode(state).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
