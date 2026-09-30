package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.domain.TimeInForce;

/** Amounts are integer atoms at the instrument's canonical scales. */
public sealed interface Command {
    record CreateAccount(AccountId accountId) implements Command {}

    record Deposit(AccountId accountId, Asset asset, long amount) implements Command {}

    record Withdraw(AccountId accountId, Asset asset, long amount) implements Command {}

    record PlaceOrder(
            AccountId accountId,
            ClientOrderId clientOrderId,
            Symbol symbol,
            Side side,
            OrderType type,
            TimeInForce timeInForce,
            long price,
            long quantity,
            Long quoteBudget)
            implements Command {}

    record CancelOrder(AccountId accountId, OrderId orderId) implements Command {}
    /** Quantity is the new remaining quantity, not the original total. */
    record ReplaceOrder(AccountId accountId, OrderId orderId, long price, long quantity) implements Command {}
}
