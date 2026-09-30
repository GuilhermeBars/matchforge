package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.domain.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties("matchforge")
public record MatchforgeProperties(Events events, Journal journal, Snapshot snapshot,
                                   List<Instrument> instruments) {
    public MatchforgeProperties {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(snapshot, "snapshot");
        instruments = List.copyOf(instruments);
        if (instruments.isEmpty()) throw new IllegalArgumentException("instruments must not be empty");
        var symbols = new HashSet<Symbol>();
        for (Instrument instrument : instruments) {
            if (!symbols.add(instrument.toDomain().symbol())) {
                throw new IllegalArgumentException("duplicate instrument: " + instrument.symbol());
            }
        }
    }

    public record Events(String publisher) {
        public Events {
            if (!List.of("in-process", "kafka").contains(publisher)) {
                throw new IllegalArgumentException("unsupported events.publisher");
            }
        }
    }

    public record Journal(String type) {
        public Journal {
            if (!List.of("postgres", "memory").contains(type)) {
                throw new IllegalArgumentException("unsupported journal.type");
            }
        }
    }

    public record Snapshot(int interval) {
        public Snapshot {
            if (interval <= 0) throw new IllegalArgumentException("snapshot interval must be positive");
        }
    }

    public record Instrument(String symbol, String baseAsset, String quoteAsset,
                             String tickSize, String lotSize, int priceScale, int quantityScale) {
        public InstrumentConfig toDomain() {
            return new InstrumentConfig(new Symbol(symbol), new Asset(baseAsset), new Asset(quoteAsset),
                    Price.parse(tickSize, priceScale), Quantity.parse(lotSize, quantityScale),
                    priceScale, quantityScale);
        }
    }
}
