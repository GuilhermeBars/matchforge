package io.github.guilhermebars.matchforge.service;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.*;
import java.util.*;

/** Atomically published immutable read state. Ledger and balances are in engineState. */
public record ExchangeReadModel(EngineSnapshot engineState, Map<Symbol, OrderBook.Depth> books,
                                Map<OrderId, CommandResult.Outcome> orders,
                                List<DomainEvent.TradeExecuted> recentTrades) {
    public ExchangeReadModel {
        books = Map.copyOf(books); orders = Map.copyOf(orders); recentTrades = List.copyOf(recentTrades);
    }
    public long sequence() { return engineState.lastSequence(); }
}
