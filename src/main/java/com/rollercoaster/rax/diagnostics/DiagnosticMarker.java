package com.rollercoaster.rax.diagnostics;

import java.util.Locale;

public record DiagnosticMarker(
        int line,
        int column,
        int length,
        String severity,
        String code,
        String message
) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO;

        public String label() {
            return switch (this) {
                case ERROR -> "ERROR";
                case WARNING -> "WARNING";
                case INFO -> "INFO";
            };
        }

        public static Severity fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return ERROR;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "WARNING", "WARN" -> WARNING;
                case "INFO", "INFORMATION" -> INFO;
                default -> ERROR;
            };
        }
    }

    public DiagnosticMarker {
        line = Math.max(line, 1);
        column = Math.max(column, 0);
        length = Math.max(length, 1);
        severity = Severity.fromString(severity).label();
        code = code != null ? code : "ERR_RAX_SYNTAX";
        message = message != null ? message : "";
    }

    public Severity severityEnum() {
        return Severity.fromString(severity);
    }

    public static DiagnosticMarker error(int line, int column, int length, String code, String message) {
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                Severity.ERROR.label(),
                code,
                message
        );
    }

    public static DiagnosticMarker warning(int line, int column, int length, String code, String message) {
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                Severity.WARNING.label(),
                code,
                message
        );
    }

    public static DiagnosticMarker info(int line, int column, int length, String code, String message) {
        return new DiagnosticMarker(
                line,
                column,
                Math.max(length, 1),
                Severity.INFO.label(),
                code,
                message
        );
    }
}
