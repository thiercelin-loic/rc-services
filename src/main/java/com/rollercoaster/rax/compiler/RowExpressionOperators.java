package com.rollercoaster.rax.compiler;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Strongly typed operators, date-diff units, inferred column types, and aggregation
 * functions used during RAX row-context and calculated-table evaluation.
 */
public final class RowExpressionOperators {

    private RowExpressionOperators() {
    }

    public enum ComparisonOperator {
        GT, LT, GTE, LTE, EQ, NEQ;

        public static ComparisonOperator fromSymbol(String symbol) {
            Objects.requireNonNull(symbol, "Comparison operator symbol must not be null");
            return switch (symbol.trim()) {
                case ">" -> GT;
                case "<" -> LT;
                case ">=" -> GTE;
                case "<=" -> LTE;
                case "=" -> EQ;
                case "<>", "!=" -> NEQ;
                default -> throw new IllegalArgumentException("Unsupported comparison operator: " + symbol);
            };
        }

        public boolean evaluate(double left, double right) {
            return switch (this) {
                case GT -> left > right;
                case LT -> left < right;
                case GTE -> left >= right;
                case LTE -> left <= right;
                case EQ -> Double.compare(left, right) == 0;
                case NEQ -> Double.compare(left, right) != 0;
            };
        }

        public boolean evaluateObjects(Object left, Object right) {
            Double parsedLeft = RowExpressionParser.tryParseDouble(left);
            Double parsedRight = RowExpressionParser.tryParseDouble(right);
            if (parsedLeft != null && parsedRight != null) {
                return evaluate(parsedLeft, parsedRight);
            }
            String leftStr = left != null ? String.valueOf(left).trim() : "";
            String rightStr = right != null ? RowExpressionParser.unquote(String.valueOf(right).trim()) : "";
            int cmp = leftStr.compareToIgnoreCase(rightStr);
            return switch (this) {
                case GT -> cmp > 0;
                case LT -> cmp < 0;
                case GTE -> cmp >= 0;
                case LTE -> cmp <= 0;
                case EQ -> cmp == 0;
                case NEQ -> cmp != 0;
            };
        }
    }

    public enum DateDiffUnit {
        DAY, MONTH, YEAR;

        public static DateDiffUnit fromToken(String token) {
            Objects.requireNonNull(token, "DateDiff unit must not be null");
            return switch (token.trim().toUpperCase(Locale.ROOT)) {
                case "DAY" -> DAY;
                case "MONTH" -> MONTH;
                case "YEAR" -> YEAR;
                default -> throw new IllegalArgumentException("Unsupported DATEDIFF unit: " + token);
            };
        }

        public long between(LocalDate startInclusive, LocalDate endExclusive) {
            return switch (this) {
                case DAY -> ChronoUnit.DAYS.between(startInclusive, endExclusive);
                case MONTH -> (endExclusive.getYear() - startInclusive.getYear()) * 12L
                        + (endExclusive.getMonthValue() - startInclusive.getMonthValue());
                case YEAR -> endExclusive.getYear() - startInclusive.getYear();
            };
        }
    }

    public enum InferredDataType {
        BOOLEAN, INT, DECIMAL, DATE, VARCHAR;

        public String defaultSummarization() {
            return switch (this) {
                case INT, DECIMAL -> "Sum";
                case BOOLEAN, DATE, VARCHAR -> "None";
            };
        }
    }

    public enum AggregateFunction {
        SUM, AVERAGE, MIN, MAX, COUNT;

        public static AggregateFunction fromToken(String token) {
            Objects.requireNonNull(token, "Aggregate function token must not be null");
            return switch (token.trim().toUpperCase(Locale.ROOT)) {
                case "SUM" -> SUM;
                case "AVERAGE", "AVG" -> AVERAGE;
                case "MIN" -> MIN;
                case "MAX" -> MAX;
                case "COUNT" -> COUNT;
                default -> throw new IllegalArgumentException("Unsupported aggregate function: " + token);
            };
        }

        public double compute(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return 0.0;
            }
            return switch (this) {
                case SUM -> RowExpressionParser.roundTwoDecimals(
                        values.stream().mapToDouble(Double::doubleValue).sum());
                case AVERAGE -> RowExpressionParser.roundTwoDecimals(
                        values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
                case MIN -> RowExpressionParser.roundTwoDecimals(
                        values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
                case MAX -> RowExpressionParser.roundTwoDecimals(
                        values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0));
                case COUNT -> values.size();
            };
        }
    }
}
