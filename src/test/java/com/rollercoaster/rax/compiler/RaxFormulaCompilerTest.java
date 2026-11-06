package com.rollercoaster.rax.compiler;

import com.rollercoaster.rax.ast.CompilationPlan;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.rax.context.FilterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RaxFormulaCompilerTest {

    private RaxFormulaCompiler compiler;

    @BeforeEach
    void setUp() {
        ContextEvaluator contextEvaluator = new ContextEvaluator();
        TimeIntelligenceCompiler timeIntelligenceCompiler = new TimeIntelligenceCompiler();
        compiler = new RaxFormulaCompiler(contextEvaluator, timeIntelligenceCompiler);
    }

    @Nested
    @DisplayName("CALCULATE SQL Generation")
    class CalculateSqlGenerationTests {

        @Test
        @DisplayName("DuckDB dialect compiles CALCULATE with FILTER clause")
        void testCalculateDuckDbDialect() {
            String formula = "CALCULATE(SUM(Sales[Amount]), Sales[Region] = \"Europe\")";
            CompilationPlan plan = compiler.compile(
                    formula,
                    SqlDialect.DUCKDB,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.sqlExpression())
                    .isEqualTo("SUM(sales.amount) FILTER (WHERE sales.region = 'Europe')");
            assertThat(plan.referencedTables()).contains("Sales");
            assertThat(plan.referencedColumns()).contains("Sales[Amount]", "Sales[Region]");
            assertThat(plan.fullSelectQuery())
                    .contains("SELECT SUM(sales.amount) FILTER (WHERE sales.region = 'Europe') AS measure_value FROM sales");
        }

        @Test
        @DisplayName("MySQL dialect compiles CALCULATE with CASE WHEN predicate")
        void testCalculateMySqlDialect() {
            String formula = "CALCULATE(SUM(Sales[Amount]), Sales[Region] = \"Europe\")";
            CompilationPlan plan = compiler.compile(
                    formula,
                    SqlDialect.MYSQL,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.sqlExpression())
                    .isEqualTo("SUM(CASE WHEN sales.region = 'Europe' THEN sales.amount ELSE NULL END)");
            assertThat(plan.referencedTables()).contains("Sales");
            assertThat(plan.referencedColumns()).contains("Sales[Amount]", "Sales[Region]");
        }

        @Test
        @DisplayName("DuckDB dialect compiles DISTINCTCOUNT CALCULATE with FILTER clause")
        void testDistinctCountDuckDb() {
            String formula = "CALCULATE(DISTINCTCOUNT(Sales[CustomerId]), Sales[Year] = 2024)";
            CompilationPlan plan = compiler.compile(
                    formula,
                    SqlDialect.DUCKDB,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.sqlExpression())
                    .isEqualTo("COUNT(DISTINCT sales.customerid) FILTER (WHERE sales.year = 2024)");
        }

        @Test
        @DisplayName("MySQL dialect compiles DISTINCTCOUNT CALCULATE with COUNT(DISTINCT CASE WHEN)")
        void testDistinctCountMySql() {
            String formula = "CALCULATE(DISTINCTCOUNT(Sales[CustomerId]), Sales[Year] = 2024)";
            CompilationPlan plan = compiler.compile(
                    formula,
                    SqlDialect.MYSQL,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.sqlExpression())
                    .isEqualTo("COUNT(DISTINCT CASE WHEN sales.year = 2024 THEN sales.customerid ELSE NULL END)");
        }

        @Test
        @DisplayName("Registers table references from ALL modifier")
        void testAllModifierRegistration() {
            String formula = "CALCULATE(SUM(Sales[Amount]), ALL(Sales))";
            CompilationPlan plan = compiler.compile(
                    formula,
                    SqlDialect.DUCKDB,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.referencedTables()).contains("Sales");
            assertThat(plan.sqlExpression()).isEqualTo("SUM(sales.amount)");
        }

        @Test
        @DisplayName("Extracts executable expression from multi-line DEFINE MEASURE and EVALUATE block")
        void testMultiLineDefineMeasureEvaluateExtraction() {
            String queryScript = """
                    DEFINE
                        MEASURE 'Sales'[Total Revenue] = SUM(Sales[Amount])
                    EVALUATE
                        SUMMARIZECOLUMNS('Sales'[Region], "Total Revenue", [Total Revenue])
                    ORDER BY 'Sales'[Region] ASC
                    """;
            CompilationPlan plan = compiler.compile(
                    queryScript,
                    SqlDialect.DUCKDB,
                    1,
                    new FilterContext(),
                    null,
                    Map.of()
            );

            assertThat(plan.sqlExpression()).isEqualTo("(SUM(sales.amount))");
            assertThat(plan.referencedTables()).contains("Sales");
        }
    }
}
