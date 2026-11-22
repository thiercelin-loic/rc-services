package com.rollercoaster.rax.diagnostics;

public record DiagnosticMarker(
        int line,
        int column,
        int length,
        String severity,
        String code,
        String message
) {
    public static DiagnosticMarker error(int line, int column, int length, String code, String message) {
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                "ERROR",
                code,
                message
        );
    }

    public static DiagnosticMarker warning(int line, int column, int length, String code, String message) {
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                "WARNING",
                code,
                message
        );
    }
}
