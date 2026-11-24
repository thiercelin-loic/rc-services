package com.rollercoaster.model;

import java.util.Locale;

public enum CrossFilterDirection {
    SINGLE,
    BOTH;

    public String toRsdlLabel() {
        return switch (this) {
            case SINGLE -> "Single";
            case BOTH -> "Both";
        };
    }

    public static CrossFilterDirection fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("CrossFilterDirection token must not be blank.");
        }
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "SINGLE", "ONEWAY", "ONE_WAY" -> SINGLE;
            case "BOTH", "BIDIRECTIONAL", "BI_DIRECTIONAL" -> BOTH;
            default -> throw new IllegalArgumentException("Unsupported cross-filter direction: " + token);
        };
    }
}

