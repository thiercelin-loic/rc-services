package com.rollercoaster.model;

/**
 * Immutable sort specification for ordering visual aggregation payloads.
 */
public record SortSpec(String field, SortDirection direction) {

    public SortSpec {
        field = field != null ? field.trim() : "";
        direction = direction != null ? direction : SortDirection.DESC;
    }
}
