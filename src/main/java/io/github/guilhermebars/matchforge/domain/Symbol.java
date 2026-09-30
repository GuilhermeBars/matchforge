package io.github.guilhermebars.matchforge.domain;

import java.util.Objects;

public record Symbol(String value) {
    public Symbol {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Z][A-Z0-9]{0,11}-[A-Z][A-Z0-9]{0,11}")) throw new IllegalArgumentException("invalid Symbol: " + value);
    }

    @Override public String toString() { return value; }
}
