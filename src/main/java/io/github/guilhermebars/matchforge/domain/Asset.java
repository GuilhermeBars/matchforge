package io.github.guilhermebars.matchforge.domain;

import java.util.Objects;

public record Asset(String value) {
    public Asset {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Z][A-Z0-9]{0,11}")) throw new IllegalArgumentException("invalid Asset: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
