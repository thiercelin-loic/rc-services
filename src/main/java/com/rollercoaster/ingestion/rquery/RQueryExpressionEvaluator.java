package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates R-Query row-level predicates, conditional expressions, string concatenations, and arithmetic.
 */
final class RQueryExpressionEvaluator {

    private static final Pattern BRACKET_COL_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "(?i)^if\\s+(.+?)\\s+then\\s+(.+?)(?:\\s+else\\s+(.+))?$");

    Object evaluateCustomColumn(Map<String, Object> row, String expression, DataType targetType) {
        if (expression == null || expression.isBlank()) {
            return targetType == DataType.UTF8_STRING ? "" : 0.0;
        }
        String trimmed = expression.trim();

        if (trimmed.contains("Text.Select")) {
            Matcher m = BRACKET_COL_PATTERN.matcher(trimmed);
            if (m.find()) {
                Object raw = row.get(m.group(1));
                return raw == null ? "" : raw.toString().replaceAll("[^0-9]", "");
            }
        }

        if (trimmed.toLowerCase().startsWith("if ")) {
            Matcher cm = CONDITIONAL_PATTERN.matcher(trimmed);
            if (cm.matches()) {
                boolean matched = evaluatePredicate(row, cm.group(1).trim());
                String chosen = matched
                        ? cm.group(2).trim()
                        : (cm.group(3) != null ? cm.group(3).trim() : "null");
                return resolveTokenValue(row, chosen);
            }
        }

        if (trimmed.contains("&")) {
            StringBuilder sb = new StringBuilder();
            for (String part : trimmed.split("&")) {
                Object v = resolveTokenValue(row, part.trim());
                if (v != null) {
                    sb.append(v);
                }
            }
            return sb.toString();
        }

        return evaluateArithmetic(row, trimmed);
    }

    boolean evaluatePredicate(Map<String, Object> row, String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        if (predicate.toLowerCase().contains(" and ")) {
            for (String clause : predicate.split("(?i)\\s+and\\s+")) {
                if (!evaluateSingleClause(row, clause.trim())) {
                    return false;
                }
            }
            return true;
        }
        return evaluateSingleClause(row, predicate.trim());
    }

    double toDouble(Object val) {
        if (val == null) {
            return 0.0;
        }
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(val.toString().replaceAll("[€$£,\\s]", ""));
    }

    private Object resolveTokenValue(Map<String, Object> row, String token) {
        if (token == null || "null".equalsIgnoreCase(token)) {
            return null;
        }
        Matcher m = BRACKET_COL_PATTERN.matcher(token);
        if (m.matches()) {
            return row.get(m.group(1));
        }
        return token.replaceAll("^[\"']|[\"']$", "");
    }

    private double evaluateArithmetic(Map<String, Object> row, String expression) {
        if (expression == null || expression.isBlank()) {
            return 0.0;
        }
        Matcher matcher = BRACKET_COL_PATTERN.matcher(expression);
        List<Double> operands = new ArrayList<>();
        while (matcher.find()) {
            String colName = matcher.group(1);
            Object val = row.get(colName);
            if (val == null) {
                throw new IllegalArgumentException("Null operand for column [" + colName + "]");
            }
            operands.add(toDouble(val));
        }

        if (operands.size() >= 2) {
            double left = operands.get(0);
            double right = operands.get(1);
            if (expression.contains("/")) {
                if (Math.abs(right) < 1e-12) {
                    throw new ArithmeticException("Division by zero in R-Query custom column expression");
                }
                return left / right;
            }
            if (expression.contains("*")) {
                return left * right;
            }
            if (expression.contains("-")) {
                return left - right;
            }
            if (expression.contains("+")) {
                return left + right;
            }
        }
        return operands.isEmpty() ? 0.0 : operands.getFirst();
    }

    private boolean evaluateSingleClause(Map<String, Object> row, String clause) {
        if (clause.contains("!=") || clause.contains("<>")) {
            String[] parts = clause.split("!=|<>", 2);
            String col = stripBrackets(parts[0]);
            String expected = stripQuotes(parts[1]);
            Object actual = row.get(col);
            if ("null".equalsIgnoreCase(expected) || expected.isEmpty()) {
                return actual != null && !actual.toString().isBlank();
            }
            return actual != null && !actual.toString().equalsIgnoreCase(expected);
        }
        if (clause.contains(">=")) {
            String[] parts = clause.split(">=", 2);
            return compareValues(row.get(stripBrackets(parts[0])), stripQuotes(parts[1])) >= 0;
        }
        if (clause.contains("<=")) {
            String[] parts = clause.split("<=", 2);
            return compareValues(row.get(stripBrackets(parts[0])), stripQuotes(parts[1])) <= 0;
        }
        if (clause.contains("==")) {
            String[] parts = clause.split("==", 2);
            Object actual = row.get(stripBrackets(parts[0]));
            return actual != null && Objects.equals(actual.toString(), stripQuotes(parts[1]));
        }
        if (clause.contains(">")) {
            String[] parts = clause.split(">", 2);
            return compareValues(row.get(stripBrackets(parts[0])), stripQuotes(parts[1])) > 0;
        }
        if (clause.contains("<")) {
            String[] parts = clause.split("<", 2);
            return compareValues(row.get(stripBrackets(parts[0])), stripQuotes(parts[1])) < 0;
        }
        return true;
    }

    private static String stripBrackets(String raw) {
        return raw.replace("[", "").replace("]", "").trim();
    }

    private static String stripQuotes(String raw) {
        return raw.replace("\"", "").replace("'", "").trim();
    }

    private int compareValues(Object actual, String rhs) {
        if (actual == null) {
            return -1;
        }
        try {
            return Double.compare(toDouble(actual), Double.parseDouble(rhs));
        } catch (NumberFormatException ignored) {
            return actual.toString().compareTo(rhs);
        }
    }
}
