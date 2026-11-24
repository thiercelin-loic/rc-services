package com.rollercoaster.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Sort direction for visual aggregation payloads.
 */
public enum SortDirection {
    ASC,
    DESC;

    @JsonCreator
    public static SortDirection fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("SortDirection token must not be blank.");
        }
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "ASC", "ASCENDING" -> ASC;
            case "DESC", "DESCENDING" -> DESC;
            default -> throw new IllegalArgumentException("Unsupported sort direction: " + token);
        };
    }
}
