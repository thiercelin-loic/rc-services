package com.rollercoaster.model;

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
}
