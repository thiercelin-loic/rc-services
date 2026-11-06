package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AnalyticalArchetypeLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticalArchetypeLibrary.class);

    private static final String DEFAULT_FACT_TABLE = "fact_store_sales";
    private static final String DEFAULT_CALENDAR_TABLE = "dim_calendar";
    private static final String FISCAL_YEAR_COLUMN = "fiscal_year";
    private static final int DEFAULT_MOVING_AVERAGE_WINDOW_DAYS = -30;
    private static final int DEFAULT_MOVING_AVERAGE_PRECEDING_ROWS = 29;

    public enum Archetype {
        COMPARISON,
        CUMULATIVE,
        SEASONALITY,
        RANKING,
        CONTRIBUTION,
        VARIANCE,
        PARETO,
        MOVING_AVERAGE
    }

    public String generateArchetypeRaxFormula(
            Archetype archetype,
            String baseMeasure,
            String dimensionColumn,
            String dateColumn) {
        Objects.requireNonNull(archetype, "Archetype cannot be null");
        Objects.requireNonNull(baseMeasure, "Base measure cannot be null");

        LOGGER.debug("Generating RAX formula for archetype '{}', measure: '{}'", archetype, baseMeasure);

        return switch (archetype) {
            case COMPARISON -> baseMeasure;
            case CUMULATIVE -> buildCumulativeFormula(dateColumn, dimensionColumn, baseMeasure);
            case SEASONALITY -> buildSeasonalityFormula(dateColumn, baseMeasure);
            case RANKING -> buildRankingFormula(dimensionColumn, baseMeasure);
            case CONTRIBUTION -> buildContributionFormula(baseMeasure, dimensionColumn);
            case VARIANCE -> buildVarianceFormula(dateColumn, dimensionColumn, baseMeasure);
            case PARETO -> buildParetoFormula(baseMeasure, dimensionColumn);
            case MOVING_AVERAGE -> buildMovingAverageFormula(dateColumn, baseMeasure);
        };
    }

    public String compileArchetypeSql(
            Archetype archetype,
            String factTable,
            String metricColumn,
            String groupColumn,
            String dateColumn,
            SqlDialect dialect) {
        Objects.requireNonNull(archetype, "Archetype cannot be null");
        Objects.requireNonNull(factTable, "Fact table cannot be null");
        Objects.requireNonNull(metricColumn, "Metric column cannot be null");

        LOGGER.debug("Compiling SQL for archetype '{}' with dialect '{}'", archetype, dialect);

        return switch (archetype) {
            case COMPARISON -> compileComparisonSql(factTable, metricColumn, groupColumn);
            case CUMULATIVE -> compileCumulativeSql(factTable, metricColumn, dateColumn);
            case SEASONALITY -> compileSeasonalitySql(factTable, metricColumn, groupColumn, dateColumn, dialect);
            case RANKING -> compileRankingSql(factTable, metricColumn, groupColumn);
            case CONTRIBUTION -> compileContributionSql(factTable, metricColumn, groupColumn);
            case VARIANCE -> compileVarianceSql(factTable, metricColumn, dateColumn);
            case PARETO -> compileParetoSql(factTable, metricColumn, groupColumn);
            case MOVING_AVERAGE -> compileMovingAverageSql(factTable, metricColumn, dateColumn);
        };
    }

    private String buildCumulativeFormula(String dateColumn, String dimensionColumn, String baseMeasure) {
        return """
                VAR CurrentDate = MAX(%s)
                RETURN
                IF(
                    ISINSCOPE(%s),
                    CALCULATE(%s, %s <= CurrentDate)
                )""".formatted(dateColumn, dimensionColumn, baseMeasure, dateColumn);
    }

    private String buildSeasonalityFormula(String dateColumn, String baseMeasure) {
        return """
                VAR MaxMonth = CALCULATE(MONTH(MAX(%s)), ALL('%s'))
                RETURN
                CALCULATE(%s, MONTH(%s) <= MaxMonth)""".formatted(dateColumn, DEFAULT_FACT_TABLE, baseMeasure, dateColumn);
    }

    private String buildRankingFormula(String dimensionColumn, String baseMeasure) {
        return """
                VAR PriorYear = SELECTEDVALUE('%s'[%s]) - 1
                RETURN
                IF(
                    ISINSCOPE(%s),
                    RANKX(ALL(%s), CALCULATE(%s, '%s'[%s] == PriorYear))
                )""".formatted(
                DEFAULT_CALENDAR_TABLE, FISCAL_YEAR_COLUMN,
                dimensionColumn,
                dimensionColumn,
                baseMeasure,
                DEFAULT_CALENDAR_TABLE, FISCAL_YEAR_COLUMN);
    }

    private String buildContributionFormula(String baseMeasure, String dimensionColumn) {
        return """
                DIVIDE(
                    %s,
                    CALCULATE(%s, ALL(%s)),
                    0
                )""".formatted(baseMeasure, baseMeasure, dimensionColumn);
    }

    private String buildVarianceFormula(String dateColumn, String dimensionColumn, String baseMeasure) {
        return """
                VAR PrevMonth = DATEADD(%s, -1, MONTH)
                RETURN
                IF(
                    ISINSCOPE(%s),
                    %s - CALCULATE(%s, PrevMonth)
                )""".formatted(dateColumn, dimensionColumn, baseMeasure, baseMeasure);
    }

    private String buildParetoFormula(String baseMeasure, String dimensionColumn) {
        return """
                VAR CurrentAmt = %s
                VAR TotalAmt = CALCULATE(%s, ALL(%s))
                RETURN
                DIVIDE(
                    CALCULATE(%s, FILTER(ALL(%s), %s >= CurrentAmt)),
                    TotalAmt,
                    0
                )""".formatted(baseMeasure, baseMeasure, dimensionColumn, baseMeasure, dimensionColumn, baseMeasure);
    }

    private String buildMovingAverageFormula(String dateColumn, String baseMeasure) {
        return """
                AVERAGEX(
                    DATESINPERIOD(%s, MAX(%s), %d, DAY),
                    %s
                )""".formatted(dateColumn, dateColumn, DEFAULT_MOVING_AVERAGE_WINDOW_DAYS, baseMeasure);
    }

    private String compileComparisonSql(String factTable, String metricColumn, String groupColumn) {
        return "SELECT " + groupColumn + ", SUM(" + metricColumn + ") AS metric_value FROM " + factTable
                + " GROUP BY " + groupColumn + " ORDER BY metric_value DESC";
    }

    private String compileCumulativeSql(String factTable, String metricColumn, String dateColumn) {
        return "SELECT " + dateColumn + ", SUM(SUM(" + metricColumn + ")) OVER (ORDER BY " + dateColumn
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cumulative_value FROM " + factTable
                + " GROUP BY " + dateColumn + " ORDER BY " + dateColumn;
    }

    private String compileSeasonalitySql(
            String factTable,
            String metricColumn,
            String groupColumn,
            String dateColumn,
            SqlDialect dialect) {
        String monthFunction = (dialect == SqlDialect.MYSQL)
                ? "MONTH(" + dateColumn + ")"
                : "EXTRACT(MONTH FROM " + dateColumn + ")";
        return "SELECT " + groupColumn + ", SUM(" + metricColumn + ") AS seasonal_value FROM " + factTable
                + " WHERE " + monthFunction + " <= (SELECT MAX(" + monthFunction + ") FROM " + factTable + ") GROUP BY " + groupColumn;
    }

    private String compileRankingSql(String factTable, String metricColumn, String groupColumn) {
        return "SELECT " + groupColumn + ", SUM(" + metricColumn + ") AS metric_value, "
                + "DENSE_RANK() OVER (ORDER BY SUM(" + metricColumn + ") DESC) AS rank_position FROM " + factTable
                + " GROUP BY " + groupColumn;
    }

    private String compileContributionSql(String factTable, String metricColumn, String groupColumn) {
        return "SELECT " + groupColumn + ", SUM(" + metricColumn + ") / NULLIF(SUM(SUM(" + metricColumn
                + ")) OVER (), 0) AS contribution_ratio FROM " + factTable + " GROUP BY " + groupColumn;
    }

    private String compileVarianceSql(String factTable, String metricColumn, String dateColumn) {
        return "SELECT " + dateColumn + ", SUM(" + metricColumn + ") - LAG(SUM(" + metricColumn
                + "), 1, 0) OVER (ORDER BY " + dateColumn + ") AS variance_delta FROM " + factTable
                + " GROUP BY " + dateColumn + " ORDER BY " + dateColumn;
    }

    private String compileParetoSql(String factTable, String metricColumn, String groupColumn) {
        return "WITH grouped AS (SELECT " + groupColumn + ", SUM(" + metricColumn + ") AS amt FROM " + factTable
                + " GROUP BY " + groupColumn + ") "
                + "SELECT " + groupColumn + ", amt, SUM(amt) OVER (ORDER BY amt DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) "
                + "/ NULLIF(SUM(amt) OVER (), 0) AS pareto_cumulative_ratio FROM grouped ORDER BY amt DESC";
    }

    private String compileMovingAverageSql(String factTable, String metricColumn, String dateColumn) {
        return "SELECT " + dateColumn + ", AVG(SUM(" + metricColumn + ")) OVER (ORDER BY " + dateColumn
                + " ROWS BETWEEN " + DEFAULT_MOVING_AVERAGE_PRECEDING_ROWS + " PRECEDING AND CURRENT ROW) AS moving_avg_30d FROM " + factTable
                + " GROUP BY " + dateColumn + " ORDER BY " + dateColumn;
    }
}
