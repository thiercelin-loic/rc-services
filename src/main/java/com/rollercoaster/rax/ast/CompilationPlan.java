package com.rollercoaster.rax.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CompilationPlan(
        String rawRaxExpression,
        SqlDialect dialect,
        String sqlExpression,
        String fullSelectQuery,
        Map<String, Object> bindParameters,
        List<String> referencedTables,
        List<String> referencedColumns,
        List<String> referencedMeasures,
        List<String> activeRelationshipsUsed,
        boolean usesIteratorScan,
        boolean usesTimeIntelligence
) {
    public CompilationPlan {
        bindParameters = bindParameters != null ? Collections.unmodifiableMap(new LinkedHashMap<>(bindParameters)) : Map.of();
        referencedTables = referencedTables != null ? List.copyOf(referencedTables) : List.of();
        referencedColumns = referencedColumns != null ? List.copyOf(referencedColumns) : List.of();
        referencedMeasures = referencedMeasures != null ? List.copyOf(referencedMeasures) : List.of();
        activeRelationshipsUsed = activeRelationshipsUsed != null ? List.copyOf(activeRelationshipsUsed) : List.of();
    }
}
