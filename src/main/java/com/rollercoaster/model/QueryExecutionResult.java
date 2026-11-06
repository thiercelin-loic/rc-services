package com.rollercoaster.model;

import com.rollercoaster.rax.ast.SqlDialect;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates the result of a Dual-Engine analytical query execution.
 */
public record QueryExecutionResult(
        String cacheKey,
        boolean cacheHit,
        SqlDialect routedEngine,
        long executionTimeMs,
        String executedSql,
        List<Map<String, Object>> rows
) {}
