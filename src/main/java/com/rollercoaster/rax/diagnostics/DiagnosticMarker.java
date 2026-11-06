package com.rollercoaster.rax.diagnostics;

public record DiagnosticMarker(
        int line,
        int column,
        int length,
        String severity,
        String code,
        String message,
        String documentationUrl
) {
    public static DiagnosticMarker error(int line, int column, int length, String code, String message) {
        String slug = code.toLowerCase().replace('_', '-');
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                "ERROR",
                code,
                message,
                "https://rollercoaster.com/docs/errors/" + slug
        );
    }

    public static DiagnosticMarker warning(int line, int column, int length, String code, String message) {
        String slug = code.toLowerCase().replace('_', '-');
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                "WARNING",
                code,
                message,
                "https://rollercoaster.com/docs/errors/" + slug
        );
    }
}
