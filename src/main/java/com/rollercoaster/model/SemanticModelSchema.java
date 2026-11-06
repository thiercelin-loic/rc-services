package com.rollercoaster.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the full Star Schema semantic model definition (tables, relationships, measures, and RLS rules).
 */
public record SemanticModelSchema(
        UUID modelId,
        String name,
        int fiscalYearStartMonth,
        List<TableDefinition> tables,
        List<RelationshipDefinition> relationships,
        List<MeasureDefinition> measures,
        Map<String, String> rlsRolePredicates
) {
    public SemanticModelSchema {
        fiscalYearStartMonth = (fiscalYearStartMonth >= 1 && fiscalYearStartMonth <= 12) ? fiscalYearStartMonth : 1;
        tables = tables != null ? List.copyOf(tables) : List.of();
        relationships = relationships != null ? List.copyOf(relationships) : List.of();
        measures = measures != null ? List.copyOf(measures) : List.of();
        rlsRolePredicates = rlsRolePredicates != null ? Map.copyOf(rlsRolePredicates) : Map.of();
    }
}
