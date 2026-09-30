package io.github.guilhermebars.matchforge.service;

import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.engine.EngineSnapshot;
import io.github.guilhermebars.matchforge.engine.OrderBook;
import java.util.List;
import java.util.Map;

/** Atomically published immutable read state. Ledger and balances are in engineState. */
public record ExchangeReadModel(
        EngineSnapshot engineState,
        Map<Symbol, OrderBook.Depth> books,
        Map<OrderId, CommandResult.Outcome> orders,
        List<DomainEvent.TradeExecuted> recentTrades) {
    public ExchangeReadModel {
        books = Map.copyOf(books);
        orders = Map.copyOf(orders);
        recentTrades = List.copyOf(recentTrades);
    }

    public long sequence() {
        return engineState.lastSequence();
    }
}
