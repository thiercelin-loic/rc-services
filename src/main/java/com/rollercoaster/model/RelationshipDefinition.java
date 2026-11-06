package com.rollercoaster.model;

/**
 * Represents a directed relationship edge in the Semantic Model DAG.
 */
public record RelationshipDefinition(
        String name,
        String fromTable,
        String fromColumn,
        String toTable,
        String toColumn,
        Cardinality cardinality,
        CrossFilterDirection crossFilterDirection,
        boolean isActive,
        String bridgeTable
) {
    public RelationshipDefinition(
            String name,
            String fromTable,
            String fromColumn,
            String toTable,
            String toColumn,
            Cardinality cardinality,
            CrossFilterDirection crossFilterDirection,
            boolean isActive) {
        this(name, fromTable, fromColumn, toTable, toColumn, cardinality, crossFilterDirection, isActive, null);
    }

    public String toRsdlSnippet() {
        String cardLabel = cardinality == Cardinality.ONE_TO_ONE
                ? "OneToOne"
                : cardinality == Cardinality.MANY_TO_MANY
                        ? "ManyToMany"
                        : cardinality == Cardinality.ONE_TO_MANY
                                ? "OneToMany"
                                : "ManyToOne";
        String crossFilterLabel = crossFilterDirection == CrossFilterDirection.BOTH ? "Both" : "Single";
        return """
                relationship '%s'
                  fromColumn: '%s'[%s]
                  toColumn: '%s'[%s]
                  cardinality: %s
                  crossFilter: %s
                  isActive: %s""".formatted(
                name, fromTable, fromColumn, toTable, toColumn, cardLabel, crossFilterLabel, isActive);
    }
}
