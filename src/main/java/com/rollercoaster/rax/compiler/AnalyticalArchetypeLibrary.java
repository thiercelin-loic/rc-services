package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    public record RaxFunctionCatalogEntry(String label, String signature, String doc) {
        public RaxFunctionCatalogEntry {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(signature, "signature must not be null");
            Objects.requireNonNull(doc, "doc must not be null");
        }
    }

    private static final List<RaxFunctionCatalogEntry> FUNCTION_CATALOG = List.of(
            new RaxFunctionCatalogEntry("SUM", "SUM(<column>)", "Adds all the numbers in a column."),
            new RaxFunctionCatalogEntry("AVERAGE", "AVERAGE(<column>)", "Returns the arithmetic mean of all numbers in a column."),
            new RaxFunctionCatalogEntry("AVG", "AVG(<column>)", "Returns the arithmetic mean of all numbers in a column."),
            new RaxFunctionCatalogEntry("MIN", "MIN(<column>)", "Returns the smallest value in a column."),
            new RaxFunctionCatalogEntry("MAX", "MAX(<column>)", "Returns the largest value in a column."),
            new RaxFunctionCatalogEntry("COUNT", "COUNT(<column>)", "Counts the number of non-blank cells in a column."),
            new RaxFunctionCatalogEntry("COUNTROWS", "COUNTROWS([<table>])", "Counts the number of rows in the specified table."),
            new RaxFunctionCatalogEntry("DISTINCTCOUNT", "DISTINCTCOUNT(<column>)", "Counts the number of distinct values in a column."),
            new RaxFunctionCatalogEntry("SUMX", "SUMX(<table>, <expression>)", "Returns the sum of an expression evaluated for each row in a table."),
            new RaxFunctionCatalogEntry("AVERAGEX", "AVERAGEX(<table>, <expression>)", "Calculates the average of an expression evaluated over a table."),
            new RaxFunctionCatalogEntry("MINX", "MINX(<table>, <expression>)", "Returns the smallest value that results from evaluating an expression for each row of a table."),
            new RaxFunctionCatalogEntry("MAXX", "MAXX(<table>, <expression>)", "Returns the largest value that results from evaluating an expression for each row of a table."),
            new RaxFunctionCatalogEntry("COUNTX", "COUNTX(<table>, <expression>)", "Counts the number of rows that contain a non-blank value when evaluating an expression over a table."),
            new RaxFunctionCatalogEntry("CALCULATE", "CALCULATE(<expression>[, <filter1>, ...])", "Evaluates an expression in a modified filter context."),
            new RaxFunctionCatalogEntry("CALCULATETABLE", "CALCULATETABLE(<table>[, <filter1>, ...])", "Evaluates a table expression in a modified filter context."),
            new RaxFunctionCatalogEntry("FILTER", "FILTER(<table>, <filter>)", "Returns a table that represents a subset of another table or expression."),
            new RaxFunctionCatalogEntry("ALL", "ALL([<table> | <column>[, <column2>, ...]])", "Returns all the rows in a table, or all the values in a column, ignoring any filters."),
            new RaxFunctionCatalogEntry("ALLEXCEPT", "ALLEXCEPT(<table>, <column>[, <column2>, ...])", "Removes all context filters in the table except filters that have been applied to the specified columns."),
            new RaxFunctionCatalogEntry("ALLSELECTED", "ALLSELECTED([<tableName> | <columnName>])", "Removes context filters from columns and rows in the current query, while retaining all other context filters."),
            new RaxFunctionCatalogEntry("REMOVEFILTERS", "REMOVEFILTERS([<table> | <column>[, ...]])", "Clears filters from the specified tables or columns."),
            new RaxFunctionCatalogEntry("KEEPFILTERS", "KEEPFILTERS(<expression>)", "Modifies how filters are applied while evaluating a CALCULATE or CALCULATETABLE function."),
            new RaxFunctionCatalogEntry("VALUES", "VALUES(<TableNameOrColumnName>)", "Returns a one-column table that contains the distinct values from the specified table or column."),
            new RaxFunctionCatalogEntry("DISTINCT", "DISTINCT(<column> | <table>)", "Returns a one-column table that contains the distinct values from the specified column or table."),
            new RaxFunctionCatalogEntry("DIVIDE", "DIVIDE(<numerator>, <denominator>[, <alternateresult>])", "Performs division and returns alternate result or BLANK() on division by 0."),
            new RaxFunctionCatalogEntry("IF", "IF(<logical_test>, <value_if_true>[, <value_if_false>])", "Checks a condition, and returns one value when it is TRUE, otherwise it returns a second value."),
            new RaxFunctionCatalogEntry("SWITCH", "SWITCH(<expression>, <value>, <result>[, ...][, <else>])", "Evaluates an expression against a list of values and returns one of multiple possible result expressions."),
            new RaxFunctionCatalogEntry("COALESCE", "COALESCE(<expression1>, <expression2>[, ...])", "Returns the first expression that does not evaluate to BLANK."),
            new RaxFunctionCatalogEntry("NOT", "NOT(<logical>)", "Changes FALSE to TRUE, or TRUE to FALSE."),
            new RaxFunctionCatalogEntry("AND", "AND(<logical1>, <logical2>)", "Checks whether both arguments are TRUE, and returns TRUE if both arguments are TRUE."),
            new RaxFunctionCatalogEntry("OR", "OR(<logical1>, <logical2>)", "Checks whether one of the arguments is TRUE to return TRUE."),
            new RaxFunctionCatalogEntry("BLANK", "BLANK()", "Returns a blank."),
            new RaxFunctionCatalogEntry("ISBLANK", "ISBLANK(<value>)", "Checks whether a value is blank, and returns TRUE or FALSE."),
            new RaxFunctionCatalogEntry("ISINSCOPE", "ISINSCOPE(<columnName>)", "Returns true when the specified column is the level in a hierarchy of levels."),
            new RaxFunctionCatalogEntry("SELECTEDVALUE", "SELECTEDVALUE(<columnName>[, <alternateResult>])", "Returns the value when the context for columnName has been filtered down to one distinct value only."),
            new RaxFunctionCatalogEntry("RANKX", "RANKX(<table>, <expression>[, <value>[, <order>[, <ties>]]])", "Returns the ranking of a number in a list of numbers for each row in the table argument."),
            new RaxFunctionCatalogEntry("TOPN", "TOPN(<n_value>, <table>, <orderBy_expression>[, <order>])", "Returns the top N rows of the specified table."),
            new RaxFunctionCatalogEntry("SUMMARIZE", "SUMMARIZE(<table>, <groupBy_columnName>[, ...][, <name>, <expression>])", "Returns a summary table for the requested totals over a set of groups."),
            new RaxFunctionCatalogEntry("SUMMARIZECOLUMNS", "SUMMARIZECOLUMNS(<groupBy_columnName>[, ...][, <filterTable>][, <name>, <expression>])", "Returns a summary table over a set of groups."),
            new RaxFunctionCatalogEntry("SELECTCOLUMNS", "SELECTCOLUMNS(<table>, <name>, <scalar_expression>[, ...])", "Adds calculated columns to the given table or table expression."),
            new RaxFunctionCatalogEntry("ADDCOLUMNS", "ADDCOLUMNS(<table>, <name>, <expression>[, ...])", "Adds calculated columns to the given table or table expression."),
            new RaxFunctionCatalogEntry("ROW", "ROW(<name>, <expression>[, ...])", "Returns a table with a single row containing values that result from the expressions given to each column."),
            new RaxFunctionCatalogEntry("UNION", "UNION(<table_expression1>, <table_expression2>[, ...])", "Creates a union (join) table from a pair of tables."),
            new RaxFunctionCatalogEntry("TOTALYTD", "TOTALYTD(<expression>, <dates>[, <filter>][, <year_end_date>])", "Evaluates the year-to-date value of the expression in the current context."),
            new RaxFunctionCatalogEntry("TOTALQTD", "TOTALQTD(<expression>, <dates>[, <filter>])", "Evaluates the value of the expression for the dates in the quarter to date, in the current context."),
            new RaxFunctionCatalogEntry("TOTALMTD", "TOTALMTD(<expression>, <dates>[, <filter>])", "Evaluates the value of the expression for the dates in the month to date, in the current context."),
            new RaxFunctionCatalogEntry("SAMEPERIODLASTYEAR", "SAMEPERIODLASTYEAR(<dates>)", "Returns a table that contains a column of dates shifted one year back in time."),
            new RaxFunctionCatalogEntry("DATEADD", "DATEADD(<dates>, <number_of_intervals>, <interval>)", "Returns a table that contains a column of dates, shifted either forward or backward in time."),
            new RaxFunctionCatalogEntry("DATESINPERIOD", "DATESINPERIOD(<dates>, <start_date>, <number_of_intervals>, <interval>)", "Returns a table that contains a column of dates that begins with a specified start date and continues for the specified number and type of date intervals."),
            new RaxFunctionCatalogEntry("DATESYTD", "DATESYTD(<dates>[, <year_end_date>])", "Returns a table that contains a column of the dates for the year to date, in the current context."),
            new RaxFunctionCatalogEntry("YEAR", "YEAR(<date>)", "Returns the year of a date as a four digit integer."),
            new RaxFunctionCatalogEntry("MONTH", "MONTH(<date>)", "Returns the month as a number from 1 (January) to 12 (December)."),
            new RaxFunctionCatalogEntry("DAY", "DAY(<date>)", "Returns the day of the month, a number from 1 to 31."),
            new RaxFunctionCatalogEntry("TODAY", "TODAY()", "Returns the current date."),
            new RaxFunctionCatalogEntry("NOW", "NOW()", "Returns the current date and time in datetime format."),
            new RaxFunctionCatalogEntry("USERELATIONSHIP", "USERELATIONSHIP(<columnName1>, <columnName2>)", "Specifies the relationship to be used in a specific calculation as the one that exists between columnName1 and columnName2."),
            new RaxFunctionCatalogEntry("CROSSFILTER", "CROSSFILTER(<columnName1>, <columnName2>, <direction>)", "Specifies the cross-filtering direction to be used in a calculation for a relationship that exists between two columns."),
            new RaxFunctionCatalogEntry("RELATED", "RELATED(<column>)", "Returns a related value from another table."),
            new RaxFunctionCatalogEntry("RELATEDTABLE", "RELATEDTABLE(<tableName>)", "Evaluates a table expression in a context modified by the given filters."),
            new RaxFunctionCatalogEntry("USEROBJECTID", "USEROBJECTID()", "Returns the current user's Object ID from JWT claims."),
            new RaxFunctionCatalogEntry("USERPRINCIPALNAME", "USERPRINCIPALNAME()", "Returns the user principal name."),
            new RaxFunctionCatalogEntry("USERNAME", "USERNAME()", "Returns the domain name and username from the credentials given to the system at connection time."),
            new RaxFunctionCatalogEntry("CUSTOMDATA", "CUSTOMDATA()", "Returns the content of the CustomData property in the connection string."),
            new RaxFunctionCatalogEntry("FORMAT", "FORMAT(<value>, <format_string>)", "Converts a value to text according to the specified format."),
            new RaxFunctionCatalogEntry("ROUND", "ROUND(<number>, <num_digits>)", "Rounds a number to the specified number of digits."),
            new RaxFunctionCatalogEntry("ABS", "ABS(<number>)", "Returns the absolute value of a number."),
            new RaxFunctionCatalogEntry("INFO.TABLES", "INFO.TABLES()", "Returns metadata about tables in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.COLUMNS", "INFO.COLUMNS()", "Returns metadata about columns in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.MEASURES", "INFO.MEASURES()", "Returns metadata about measures in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.RELATIONSHIPS", "INFO.RELATIONSHIPS()", "Returns metadata about relationships in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.PARTITIONS", "INFO.PARTITIONS()", "Returns metadata about storage partitions in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.CALCDEPENDENCY", "INFO.CALCDEPENDENCY()", "Returns dependency graph rows between measures and columns in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.STORAGETABLECOLUMNS", "INFO.STORAGETABLECOLUMNS()", "Returns dictionary storage size estimates for columns in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.VIEW.TABLES", "INFO.VIEW.TABLES()", "Returns metadata view of tables in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.VIEW.COLUMNS", "INFO.VIEW.COLUMNS()", "Returns metadata view of columns in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.VIEW.MEASURES", "INFO.VIEW.MEASURES()", "Returns metadata view of measures in the semantic model."),
            new RaxFunctionCatalogEntry("INFO.VIEW.RELATIONSHIPS", "INFO.VIEW.RELATIONSHIPS()", "Returns metadata view of relationships in the semantic model.")
    );

    private static final Set<String> KNOWN_FUNCTION_NAMES = FUNCTION_CATALOG.stream()
            .map(entry -> entry.label().toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    public List<RaxFunctionCatalogEntry> getFunctionCatalog() {
        return FUNCTION_CATALOG;
    }

    public boolean isKnownFunction(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        return KNOWN_FUNCTION_NAMES.contains(functionName.trim().toUpperCase(Locale.ROOT));
    }

    private String compileMovingAverageSql(String factTable, String metricColumn, String dateColumn) {
        return "SELECT " + dateColumn + ", AVG(SUM(" + metricColumn + ")) OVER (ORDER BY " + dateColumn
                + " ROWS BETWEEN " + DEFAULT_MOVING_AVERAGE_PRECEDING_ROWS + " PRECEDING AND CURRENT ROW) AS moving_avg_30d FROM " + factTable
                + " GROUP BY " + dateColumn + " ORDER BY " + dateColumn;
    }
}
