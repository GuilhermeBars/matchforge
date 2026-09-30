package io.github.guilhermebars.matchforge.risk;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.engine.Command.PlaceOrder;
import io.github.guilhermebars.matchforge.engine.RejectionReason;

/** Stateless order admission checks. Numeric inputs are canonical instrument atoms. */
public final class RiskManager {
    private RiskManager() {}
    public static RejectionReason validate(PlaceOrder order, InstrumentConfig instrument) {
        if (instrument == null) return RejectionReason.UNKNOWN_SYMBOL;
        if (order.side() == null || order.type() == null || order.timeInForce() == null
                || order.clientOrderId() == null) return RejectionReason.INVALID_ORDER;
        if (order.quantity() <= 0) return RejectionReason.INVALID_QUANTITY;
        if (order.quantity() % instrument.lotSize().units() != 0) return RejectionReason.INVALID_LOT;
        if (order.type() == OrderType.LIMIT) {
            if (order.price() <= 0 || order.price() % instrument.tickSize().units() != 0)
                return RejectionReason.INVALID_TICK;
            if (order.quoteBudget() != null) return RejectionReason.INVALID_ORDER;
        } else {
            if (order.price() != 0) return RejectionReason.INVALID_ORDER;
            if (order.side() == Side.BUY && (order.quoteBudget() == null || order.quoteBudget() <= 0))
                return RejectionReason.INVALID_QUOTE_BUDGET;
            if (order.side() == Side.SELL && order.quoteBudget() != null) return RejectionReason.INVALID_ORDER;
        }
        return null;
    }
    public static long reservation(PlaceOrder order) {
        if (order.side() == Side.SELL) return order.quantity();
        return order.type() == OrderType.MARKET ? order.quoteBudget() : Math.multiplyExact(order.price(), order.quantity());
    }
}
