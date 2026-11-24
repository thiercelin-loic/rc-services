package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.Cardinality;
import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.CrossFilterDirection;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.RelationshipDefinition;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RaxQueryFormatterTest {

    private RaxQueryFormatter formatter;
    private SemanticModelSchema sampleSchema;

    @BeforeEach
    void setUp() {
        formatter = new RaxQueryFormatter();
        sampleSchema = new SemanticModelSchema(
                UUID.randomUUID(),
                "Telemetry Analytics",
                1,
                List.of(
                        new TableDefinition(
                                "events",
                                "FACT",
                                null,
                                2500L,
                                false,
                                "Event stream table",
                                List.of(
                                        new ColumnDefinition("event_id", "BIGINT", true, false),
                                        new ColumnDefinition("host_id", "VARCHAR", false, true),
                                        new ColumnDefinition("latency_ms", "DOUBLE", false, false)
                                )
                        ),
                        new TableDefinition(
                                "hosts",
                                "DIMENSION",
                                null,
                                50L,
                                false,
                                "Host metadata table",
                                List.of(
                                        new ColumnDefinition("host_id", "VARCHAR", true, false),
                                        new ColumnDefinition("datacenter", "VARCHAR", false, false)
                                )
                        )
                ),
                List.of(
                        new RelationshipDefinition(
                                "rel_events_hosts",
                                "events",
                                "host_id",
                                "hosts",
                                "host_id",
                                Cardinality.MANY_TO_ONE,
                                CrossFilterDirection.SINGLE,
                                true,
                                null
                        )
                ),
                List.of(
                        new MeasureDefinition(
                                "m_avg_latency",
                                "Avg Latency",
                                "events",
                                "AVERAGE(events[latency_ms])",
                                "#,##0.00",
                                "Average event latency in milliseconds"
                        ),
                        new MeasureDefinition(
                                "m_max_latency",
                                "Max Latency",
                                "events",
                                "MAX(events[latency_ms])",
                                "#,##0.00",
                                "Peak event latency"
                        )
                ),
                Map.of()
        );
    }

    @Test
    @DisplayName("Formats single-line DEFINE MEASURE and EVALUATE script into indented blocks")
    void testFormatDefineAndEvaluateQuery() {
        String raw = "define measure 'events'[Avg Latency] = average(events[latency_ms]) evaluate summarizecolumns('hosts'[datacenter], \"Avg Latency\", [Avg Latency]) order by 'hosts'[datacenter] asc";

        String formatted = formatter.formatQuery(raw);

        assertThat(formatted)
                .startsWith("DEFINE\n    MEASURE 'events'[Avg Latency] = AVERAGE(events[latency_ms])")
                .contains("\nEVALUATE\n    SUMMARIZECOLUMNS(\n")
                .contains("'hosts'[datacenter],\n")
                .contains("\"Avg Latency\",\n")
                .endsWith("ORDER BY 'hosts'[datacenter] ASC");
    }

    @Test
    @DisplayName("Formats VAR / RETURN expressions and preserves INFO.* dotted function calls")
    void testFormatVarReturnAndInfoFunctions() {
        String varFormula = "var baseVal = sum(events[latency_ms]) return divide(baseVal, 100, 0)";
        String formattedVar = formatter.formatQuery(varFormula);

        assertThat(formattedVar)
                .contains("VAR baseVal = SUM(events[latency_ms])")
                .contains("RETURN")
                .contains("DIVIDE(\n");

        String infoScript = "evaluate info.calcdependency()";
        assertThat(formatter.formatQuery(infoScript))
                .isEqualTo("EVALUATE\n    INFO.CALCDEPENDENCY()");
    }

    @Test
    @DisplayName("Generates complete TMDL script from SemanticModelSchema")
    void testGenerateModelTmdl() {
        String tmdl = formatter.generateModelTmdl(sampleSchema);

        assertThat(tmdl)
                .startsWith("createOrReplace")
                .contains("table 'events'")
                .contains("/// Average event latency in milliseconds")
                .contains("measure 'Avg Latency' = AVERAGE(events[latency_ms])")
                .contains("formatString: \"#,##0.00\"")
                .contains("column 'latency_ms'")
                .contains("dataType: double")
                .contains("relationship 'rel_events_hosts'")
                .contains("fromColumn: 'events'[host_id]")
                .contains("toColumn: 'hosts'[host_id]");
    }

    @Test
    @DisplayName("Generates DEFINE MEASURE + EVALUATE ROW script for all model measures")
    void testDefineAllMeasures() {
        String script = formatter.defineAllMeasures(sampleSchema);

        assertThat(script)
                .startsWith("DEFINE\n")
                .contains("MEASURE 'events'[Avg Latency] = AVERAGE(events[latency_ms])")
                .contains("MEASURE 'events'[Max Latency] = MAX(events[latency_ms])")
                .contains("EVALUATE\n    ROW(")
                .contains("\"Avg Latency\", [Avg Latency]")
                .contains("\"Max Latency\", [Max Latency]");
    }
}
