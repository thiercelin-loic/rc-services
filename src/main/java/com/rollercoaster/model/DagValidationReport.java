package com.rollercoaster.model;

import com.rollercoaster.rax.diagnostics.DiagnosticMarker;

import java.util.List;

/**
 * Result of SemanticModelManager DAG consistency, cycle, and ambiguity validation.
 */
public record DagValidationReport(
        boolean valid,
        boolean hasCycles,
        boolean hasAmbiguousActivePaths,
        boolean violatesStarSchema,
        List<DiagnosticMarker> diagnostics
) {}
