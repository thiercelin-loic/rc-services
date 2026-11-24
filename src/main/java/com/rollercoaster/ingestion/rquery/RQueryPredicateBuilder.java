package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds R-Query filter predicate strings from strongly typed filter operators
 * with proper quoting for numeric versus text literals.
 */
public final class RQueryPredicateBuilder {

    private static final Pattern NUMERIC_LITERAL_PATTERN = Pattern.compile("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");

    private RQueryPredicateBuilder() {
    }

    public enum FilterOperator {
        EQUALS("=="),
        NOT_EQUALS("!="),
        GREATER_THAN(">"),
        GREATER_OR_EQUAL(">="),
        LESS_THAN("<"),
        LESS_OR_EQUAL("<="),
        BETWEEN("BETWEEN");

        private final String symbol;

        FilterOperator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public static FilterOperator fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return EQUALS;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "EQUALS", "==", "=", "EQ" -> EQUALS;
                case "NOT_EQUALS", "!=", "<>", "NE", "NEQ" -> NOT_EQUALS;
                case "GREATER_THAN", ">", "GT" -> GREATER_THAN;
                case "GREATER_OR_EQUAL", "GREATER_THAN_OR_EQUAL", ">=", "GTE", "GE" -> GREATER_OR_EQUAL;
                case "LESS_THAN", "<", "LT" -> LESS_THAN;
                case "LESS_OR_EQUAL", "LESS_THAN_OR_EQUAL", "<=", "LTE", "LE" -> LESS_OR_EQUAL;
                case "BETWEEN", "RANGE" -> BETWEEN;
                default -> throw new IllegalArgumentException("Unsupported filter operator: " + raw);
            };
        }
    }

    public record FilterCriterion(
            String column,
            FilterOperator operator,
            String value,
            String secondValue,
            boolean numeric
    ) {
        public FilterCriterion {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Filter column name must not be null or blank");
            }
            Objects.requireNonNull(operator, "Filter operator must not be null");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Filter value must not be null or blank");
            }
            if (operator == FilterOperator.BETWEEN && (secondValue == null || secondValue.isBlank())) {
                throw new IllegalArgumentException("BETWEEN filter operator requires a non-blank secondValue");
            }
        }

        public static FilterCriterion of(String column, FilterOperator operator, String value, DataType dataType) {
            boolean isNum = dataType != null ? dataType.isNumeric() : isNumericLiteral(value);
            return new FilterCriterion(column, operator, value, null, isNum);
        }

        public static FilterCriterion between(String column, String lowerBound, String upperBound, DataType dataType) {
            boolean isNum = dataType != null
                    ? dataType.isNumeric()
                    : (isNumericLiteral(lowerBound) && isNumericLiteral(upperBound));
            return new FilterCriterion(column, FilterOperator.BETWEEN, lowerBound, upperBound, isNum);
        }
    }

    public static String buildPredicate(FilterCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        String colRef = formatColumnRef(criterion.column());
        return switch (criterion.operator()) {
            case EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL ->
                    colRef + " " + criterion.operator().symbol() + " " + formatLiteral(criterion.value(), criterion.numeric());
            case BETWEEN -> colRef + " >= " + formatLiteral(criterion.value(), criterion.numeric())
                    + " and " + colRef + " <= " + formatLiteral(criterion.secondValue(), criterion.numeric());
        };
    }

    public static String buildPredicate(String column, FilterOperator operator, String value) {
        boolean numeric = isNumericLiteral(value);
        return buildPredicate(new FilterCriterion(column, operator, value, null, numeric));
    }

    public static String buildPredicate(String column, FilterOperator operator, String value, DataType columnType) {
        return buildPredicate(FilterCriterion.of(column, operator, value, columnType));
    }

    public static String buildBetweenPredicate(String column, String lowerBound, String upperBound, DataType columnType) {
        return buildPredicate(FilterCriterion.between(column, lowerBound, upperBound, columnType));
    }

    public static boolean isNumericLiteral(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return NUMERIC_LITERAL_PATTERN.matcher(raw.trim()).matches();
    }

    private static String formatColumnRef(String column) {
        String trimmed = column.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed;
        }
        return "[" + trimmed + "]";
    }

    private static String formatLiteral(String rawValue, boolean numeric) {
        String trimmed = rawValue.trim();
        if ("null".equalsIgnoreCase(trimmed)) {
            return "null";
        }
        String unquoted = stripOuterQuotes(trimmed);
        if (numeric && isNumericLiteral(unquoted)) {
            return unquoted;
        }
        return "'" + unquoted.replace("'", "") + "'";
    }

    private static String stripOuterQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }
}
