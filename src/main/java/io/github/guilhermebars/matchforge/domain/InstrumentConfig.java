package io.github.guilhermebars.matchforge.domain;

import java.util.Objects;

/** Canonical scales and admissible price/quantity increments for one market. */
public record InstrumentConfig(Symbol symbol, Asset baseAsset, Asset quoteAsset,
                               Price tickSize, Quantity lotSize, int priceScale, int quantityScale) {
    public InstrumentConfig {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(baseAsset, "baseAsset");
        Objects.requireNonNull(quoteAsset, "quoteAsset");
        Objects.requireNonNull(tickSize, "tickSize");
        Objects.requireNonNull(lotSize, "lotSize");
        FixedPoint.validateScale(priceScale);
        FixedPoint.validateScale(quantityScale);
        if (baseAsset.equals(quoteAsset)) throw new IllegalArgumentException("assets must differ");
        if (!symbol.value().equals(baseAsset.value() + "-" + quoteAsset.value())) {
            throw new IllegalArgumentException("symbol must match base-quote assets");
        }
        FixedPoint.requireSameScale(priceScale, tickSize.scale());
        FixedPoint.requireSameScale(quantityScale, lotSize.scale());
        if (tickSize.units() == 0 || lotSize.units() == 0) {
            throw new IllegalArgumentException("tick and lot must be positive");
        }
    }

    public void validatePrice(Price price) {
        Objects.requireNonNull(price, "price");
        FixedPoint.requireSameScale(priceScale, price.scale());
        if (price.units() == 0 || price.units() % tickSize.units() != 0) {
            throw new IllegalArgumentException("price must be a positive tick multiple");
        }
    }

    public void validateQuantity(Quantity quantity) {
        Objects.requireNonNull(quantity, "quantity");
        FixedPoint.requireSameScale(quantityScale, quantity.scale());
        if (quantity.units() == 0 || quantity.units() % lotSize.units() != 0) {
            throw new IllegalArgumentException("quantity must be a positive lot multiple");
        }
    }
}
