package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnRequest;
import com.rollercoaster.rax.compiler.CalculatedColumnService.CalculatedColumnResult;
import com.rollercoaster.rax.context.ContextEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatedColumnServiceTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private CalculatedColumnService service;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();
        semanticModelManager = new SemanticModelManager();
        ContextEvaluator contextEvaluator = new ContextEvaluator();
        RaxFormulaCompiler formulaCompiler = new RaxFormulaCompiler(contextEvaluator, new TimeIntelligenceCompiler());
        service = new CalculatedColumnService(
                formulaCompiler, contextEvaluator, semanticModelManager, duckDbConnectionManager);

        modelId = UUID.randomUUID();
        TableDefinition telemetryTable = new TableDefinition(
                "telemetry",
                "FACT",
                null,
                3L,
                false,
                List.of(
                        new ColumnDefinition("reading_id", "INT", true, false),
                        new ColumnDefinition("units", "INT", false, false),
                        new ColumnDefinition("rate", "DECIMAL", false, false),
                        new ColumnDefinition("status", "VARCHAR", false, false),
                        new ColumnDefinition("recorded_at", "DATE", false, false)));
        TableDefinition stationTable = new TableDefinition(
                "stations",
                "DIMENSION",
                null,
                2L,
                false,
                List.of(
                        new ColumnDefinition("station_code", "VARCHAR", true, false),
                        new ColumnDefinition("zone", "VARCHAR", false, false)));

        semanticModelManager.registerModel(new SemanticModelSchema(
                modelId, "Telemetry Analytics", 1,
                List.of(telemetryTable, stationTable), List.of(), List.of(), Map.of(), false));

        duckDbConnectionManager.ingestTableRows("telemetry", List.of(
                Map.of("reading_id", 1, "units", 10, "rate", 2.5, "status", "Active", "recorded_at", "2026-01-01"),
                Map.of("reading_id", 2, "units", 0, "rate", 4.0, "status", "Idle", "recorded_at", "2026-01-15"),
                Map.of("reading_id", 3, "units", 5, "rate", 3.0, "status", "Active", "recorded_at", "2026-02-01")));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("Evaluates safe arithmetic, row field resolution, and conditions cleanly")
    void testRowPrimitives() {
        assertThat(service.evaluateSafeArithmetic("(12.5 + 7.5) * 2 / 4")).isEqualTo(10.0);
        assertThat(service.evaluateSafeArithmetic("-5 + 12")).isEqualTo(7.0);

        Map<String, Object> row = Map.of("Units", 25, "Status", "Active");
        assertThat(service.resolveRowField(row, "[units]")).isEqualTo(25);
        assertThat(service.evaluateRowCondition("[Units] >= 20", row)).isTrue();
        assertThat(service.evaluateRowCondition("[Status] = \"Idle\"", row)).isFalse();
        assertThat(service.evaluateRowCondition("TRUE()", row)).isTrue();

        assertThatThrownBy(() -> service.evaluateSafeArithmetic("1 + [bad]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.evaluateRowCondition("[Units] ?? 5", row))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Evaluates arithmetic calculated column from DuckDB rows and persists column metadata")
    void testEvaluateArithmeticAndPersistColumn() {
        CalculatedColumnRequest request = new CalculatedColumnRequest(
                modelId, "telemetry", "weighted_value", "[units] * [rate]");

        CalculatedColumnResult result = service.evaluateCalculatedColumn(request);

        assertThat(result.columnName()).isEqualTo("weighted_value");
        assertThat(result.inferredDataType()).isEqualTo("DECIMAL");
        assertThat(result.dataType()).isEqualTo("DECIMAL");
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).get("weighted_value")).isEqualTo(25.0);
        assertThat(result.rows().get(1).get("weighted_value")).isEqualTo(0.0);
        assertThat(result.rows().get(2).get("weighted_value")).isEqualTo(15.0);
        assertThat(result.diagnostics()).isNotNull();

        TableDefinition updatedTable = semanticModelManager.getModel(modelId).tables().stream()
                .filter(t -> t.name().equals("telemetry"))
                .findFirst().orElseThrow();
        assertThat(updatedTable.columns())
                .extracting(ColumnDefinition::name)
                .contains("weighted_value");
    }

    @Test
    @DisplayName("Evaluates IF, DIVIDE, DATEDIFF, string concatenation, and CALCULATE context transition")
    void testConditionalDivideDateDiffConcatAndCalculate() {
        List<Map<String, Object>> inlineRows = List.of(
                Map.of("reading_id", 10, "units", 12, "denominator", 4,
                        "status", "Active", "recorded_at", LocalDate.now().minusDays(5).toString()),
                Map.of("reading_id", 11, "units", 8, "denominator", 0,
                        "status", "Idle", "recorded_at", LocalDate.now().minusDays(2).toString()));

        CalculatedColumnResult ifResult = service.evaluateCalculatedColumn(new CalculatedColumnRequest(
                modelId, "telemetry", "tier",
                "IF([units] >= 10, \"High\", \"Standard\")", inlineRows));
        assertThat(ifResult.inferredDataType()).isEqualTo("VARCHAR");
        assertThat(ifResult.rows().get(0).get("tier")).isEqualTo("High");
        assertThat(ifResult.rows().get(1).get("tier")).isEqualTo("Standard");

        CalculatedColumnResult divResult = service.evaluateCalculatedColumn(new CalculatedColumnRequest(
                modelId, "telemetry", "ratio",
                "DIVIDE([units], [denominator], -1)", inlineRows));
        assertThat(divResult.inferredDataType()).isEqualTo("DECIMAL");
        assertThat(divResult.rows().get(0).get("ratio")).isEqualTo(3.0);
        assertThat(divResult.rows().get(1).get("ratio")).isEqualTo(-1.0);

        CalculatedColumnResult dateDiffResult = service.evaluateCalculatedColumn(new CalculatedColumnRequest(
                modelId, "telemetry", "age_days",
                "DATEDIFF([recorded_at], TODAY(), DAY)", inlineRows));
        assertThat(dateDiffResult.inferredDataType()).isEqualTo("INT");
        assertThat(dateDiffResult.rows().get(0).get("age_days")).isEqualTo(5L);
        assertThat(dateDiffResult.rows().get(1).get("age_days")).isEqualTo(2L);

        CalculatedColumnResult concatResult = service.evaluateCalculatedColumn(new CalculatedColumnRequest(
                modelId, "telemetry", "label",
                "[status] & \"-\" & [units]", inlineRows));
        assertThat(concatResult.inferredDataType()).isEqualTo("VARCHAR");
        assertThat(concatResult.rows().get(0).get("label")).isEqualTo("Active-12");

        CalculatedColumnResult calcResult = service.evaluateCalculatedColumn(new CalculatedColumnRequest(
                modelId, "telemetry", "active_units",
                "CALCULATE(SUM(telemetry[units]), telemetry[status] = \"Active\")", inlineRows));
        assertThat(calcResult.rows().get(0).get("active_units")).isEqualTo(12.0);
        assertThat(calcResult.rows().get(1).get("active_units")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Rejects null/blank requests and unresolvable expressions fail-fast")
    void testFailFastValidation() {
        assertThatThrownBy(() -> service.evaluateCalculatedColumn(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CalculatedColumnRequest(modelId, " ", "col", "[a]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalculatedColumnRequest(modelId, "tbl", "col", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.evaluateRowContextExpression("UNSUPPORTED_FN([a])", Map.of("a", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
