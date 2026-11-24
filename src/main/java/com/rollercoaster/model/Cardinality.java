package com.rollercoaster.model;

import java.util.Locale;

public enum Cardinality {
    ONE_TO_ONE("1:1"),
    MANY_TO_ONE("*:1"),
    ONE_TO_MANY("1:N"),
    MANY_TO_MANY("M:N");

    private final String label;

    Cardinality(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public String toRsdlLabel() {
        return switch (this) {
            case ONE_TO_ONE -> "OneToOne";
            case MANY_TO_ONE -> "ManyToOne";
            case ONE_TO_MANY -> "OneToMany";
            case MANY_TO_MANY -> "ManyToMany";
        };
    }

    public static Cardinality fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Cardinality token must not be blank.");
        }
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "1:1", "ONE_TO_ONE", "ONETOONE" -> ONE_TO_ONE;
            case "*:1", "N:1", "MANY_TO_ONE", "MANYTOONE" -> MANY_TO_ONE;
            case "1:N", "1:*", "ONE_TO_MANY", "ONETOMANY" -> ONE_TO_MANY;
            case "M:N", "*:*", "N:M", "MANY_TO_MANY", "MANYTOMANY" -> MANY_TO_MANY;
            default -> throw new IllegalArgumentException("Unsupported cardinality: " + token);
        };
    }
}

