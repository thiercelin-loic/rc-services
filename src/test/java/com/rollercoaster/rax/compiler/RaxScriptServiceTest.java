package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DualEngineCoordinator;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.QueryExecutionResult;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.ast.SqlDialect;
import com.rollercoaster.rax.compiler.RaxScriptService.ApplyMeasuresResult;
import com.rollercoaster.rax.compiler.RaxScriptService.ScriptExecutionRequest;
import com.rollercoaster.rax.context.ContextEvaluator;
import com.rollercoaster.security.DynamicDataMaskingService;
import com.rollercoaster.security.RlsSecurityContextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaxScriptServiceTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private RaxScriptService scriptService;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();

        ContextEvaluator contextEvaluator = new ContextEvaluator();
        TimeIntelligenceCompiler timeIntelligenceCompiler = new TimeIntelligenceCompiler();
        RaxFormulaCompiler formulaCompiler = new RaxFormulaCompiler(contextEvaluator, timeIntelligenceCompiler);
        semanticModelManager = new SemanticModelManager();
        RlsSecurityContextResolver rlsResolver = new RlsSecurityContextResolver();
        DynamicDataMaskingService maskingService = new DynamicDataMaskingService();
        InfoFunctionEvaluator infoEvaluator = new InfoFunctionEvaluator();

        DualEngineCoordinator coordinator = new DualEngineCoordinator(
                formulaCompiler,
                duckDbConnectionManager,
                semanticModelManager,
                rlsResolver,
                maskingService,
                null,
                null,
                null,
                1_000_000L
        );

        scriptService = new RaxScriptService(
                semanticModelManager,
                coordinator,
                formulaCompiler,
                infoEvaluator
        );

        modelId = UUID.randomUUID();
        SemanticModelSchema schema = new SemanticModelSchema(
                modelId,
                "Cloud Telemetry Model",
                1,
                List.of(
                        new TableDefinition(
                                "workloads",
                                "FACT",
                                null,
                                400L,
                                false,
                                "Workload execution metrics",
                                List.of(
                                        new ColumnDefinition("service", "VARCHAR", false, false),
                                        new ColumnDefinition("zone", "VARCHAR", false, false),
                                        new ColumnDefinition("cpu_cost", "DOUBLE", false, false)
                                )
                        )
                ),
                List.of(),
                List.of(
                        new MeasureDefinition(
                                "m_total_cost",
                                "Total Cost",
                                "workloads",
                                "SUM(workloads[cpu_cost])",
                                "$#,##0.00",
                                "Total CPU cost"
                        )
                ),
                Map.of()
        );
        semanticModelManager.registerModel(schema);

        coordinator.ingestDatasetRows("default-tenant", "workloads", List.of(
                Map.of("service", "Auth", "zone", "eu-west", "cpu_cost", 150.0),
                Map.of("service", "Auth", "zone", "us-east", "cpu_cost", 250.0),
                Map.of("service", "Billing", "zone", "eu-west", "cpu_cost", 600.0),
                Map.of("service", "Search", "zone", "ap-south", "cpu_cost", 300.0)
        ));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("Routes INFO.TABLES(), INFO.MEASURES(), INFO.COLUMNS(), INFO.CALCDEPENDENCY(), and INFO.STORAGETABLECOLUMNS()")
    void testInfoFunctionsEvaluation() {
        QueryExecutionResult tablesRes = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, "EVALUATE INFO.TABLES()", SqlDialect.DUCKDB));
        assertThat(tablesRes.rows()).hasSize(1);
        assertThat(tablesRes.rows().getFirst()).containsEntry("Name", "workloads");

        QueryExecutionResult columnsRes = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, "EVALUATE INFO.COLUMNS()", SqlDialect.DUCKDB));
        assertThat(columnsRes.rows()).hasSize(3);

        QueryExecutionResult measuresRes = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, "EVALUATE INFO.MEASURES()", SqlDialect.DUCKDB));
        assertThat(measuresRes.rows()).hasSize(1);
        assertThat(measuresRes.rows().getFirst()).containsEntry("Name", "Total Cost");

        QueryExecutionResult depRes = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, "EVALUATE INFO.CALCDEPENDENCY()", SqlDialect.DUCKDB));
        assertThat(depRes.rows()).isNotEmpty();
        assertThat(depRes.rows().getFirst()).containsEntry("OBJECT", "Total Cost");

        QueryExecutionResult storageRes = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, "EVALUATE INFO.STORAGETABLECOLUMNS()", SqlDialect.DUCKDB));
        assertThat(storageRes.rows()).hasSize(3);
        assertThat(storageRes.rows().getFirst()).containsKey("DICTIONARY_SIZE");
    }

    @Test
    @DisplayName("Executes DEFINE MEASURE + EVALUATE SUMMARIZE with ORDER BY descending")
    void testExecuteDefineMeasureAndSummarizeWithOrderBy() {
        String script = """
                DEFINE
                    MEASURE 'workloads'[Service Cost] = SUM(workloads[cpu_cost])
                EVALUATE
                    SUMMARIZE('workloads', workloads[service], "Cost", [Service Cost])
                ORDER BY [Cost] DESC
                """;

        QueryExecutionResult result = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, script, SqlDialect.DUCKDB));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0)).containsEntry("service", "Billing").containsEntry("Cost", 600.0);
        assertThat(result.rows().get(1)).containsEntry("service", "Auth").containsEntry("Cost", 400.0);
        assertThat(result.rows().get(2)).containsEntry("service", "Search").containsEntry("Cost", 300.0);
    }

    @Test
    @DisplayName("Executes SELECTCOLUMNS projection and ORDER BY over table and INFO sources")
    void testExecuteSelectColumnsAndOrderBy() {
        String script = """
                EVALUATE
                    SELECTCOLUMNS('workloads', "ServiceName", workloads[service], "Zone", workloads[zone])
                ORDER BY [ServiceName] ASC
                """;

        QueryExecutionResult result = scriptService.executeScript(
                new ScriptExecutionRequest(modelId, script, SqlDialect.DUCKDB));

        assertThat(result.rows()).hasSize(4);
        assertThat(result.rows().getFirst()).containsKeys("ServiceName", "Zone");
        assertThat(result.rows().getFirst().get("ServiceName")).isEqualTo("Auth");
    }

    @Test
    @DisplayName("Extracts and persists DEFINE MEASURE definitions into SemanticModelManager")
    void testExtractAndSaveMeasuresFromScript() {
        String script = """
                DEFINE
                    MEASURE 'workloads'[Avg CPU Cost] = AVERAGE(workloads[cpu_cost])
                    MEASURE 'workloads'[Peak CPU Cost] = MAX(workloads[cpu_cost])
                EVALUATE
                    ROW("Avg", [Avg CPU Cost], "Peak", [Peak CPU Cost])
                """;

        ApplyMeasuresResult applied = scriptService.extractAndSaveMeasuresFromScript(modelId, script);

        assertThat(applied.appliedCount()).isEqualTo(2);
        assertThat(applied.measures())
                .extracting(MeasureDefinition::name)
                .contains("Total Cost", "Avg CPU Cost", "Peak CPU Cost");

        SemanticModelSchema updatedSchema = semanticModelManager.getModel(modelId);
        assertThat(updatedSchema.measures()).hasSize(3);
    }

    @Test
    @DisplayName("Rejects blank scripts with IllegalArgumentException")
    void testRejectsBlankScripts() {
        assertThatThrownBy(() -> new ScriptExecutionRequest(modelId, "   ", SqlDialect.DUCKDB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scriptService.extractAndSaveMeasuresFromScript(modelId, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
