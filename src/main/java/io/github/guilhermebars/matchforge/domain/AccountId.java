package io.github.guilhermebars.matchforge.domain;

import java.util.Objects;

public record AccountId(String value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("invalid AccountId: " + value);
    }

    @Override public String toString() { return value; }
}
