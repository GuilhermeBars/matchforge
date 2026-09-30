package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import java.time.Instant;

public sealed interface DomainEvent {
    Header header();
    record Header(long sequence, Instant timestamp) {}
    enum CancelReason { USER, IOC_REMAINDER, FOK_KILLED, MARKET_NO_LIQUIDITY, MARKET_BUDGET, REPLACED }
    record AccountCreated(Header header, AccountId accountId) implements DomainEvent {}
    record FundsDeposited(Header header, AccountId accountId, Asset asset, long amount) implements DomainEvent {}
    record FundsWithdrawn(Header header, AccountId accountId, Asset asset, long amount) implements DomainEvent {}
    record OrderAccepted(Header header, OrderId orderId, AccountId accountId) implements DomainEvent {}
    record OrderRejected(Header header, AccountId accountId, RejectionReason reason) implements DomainEvent {}
    record OrderRested(Header header, OrderBook.OpenOrder order) implements DomainEvent {}
    record TradeExecuted(Header header, long tradeId, Symbol symbol, OrderId makerOrderId,
                         OrderId takerOrderId, AccountId makerAccountId, AccountId takerAccountId,
                         long price, long quantity) implements DomainEvent {}
    record OrderCancelled(Header header, OrderId orderId, long quantity, CancelReason reason) implements DomainEvent {}
    record OrderReplaced(Header header, OrderId orderId, long price, long quantity,
                         boolean priorityRetained) implements DomainEvent {}
    record LedgerPosted(Header header, Ledger.Transaction transaction) implements DomainEvent {}
}
