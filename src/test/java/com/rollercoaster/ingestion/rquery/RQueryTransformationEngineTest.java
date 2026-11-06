package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.RecordBatch;
import com.rollercoaster.ingestion.parser.TypeInferenceEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RQueryTransformationEngineTest {

    private final RQueryTransformationEngine engine = new RQueryTransformationEngine(new TypeInferenceEngine());

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static RecordBatch batch(Map<String, DataType> schema, Map<String, Object>... rows) {
        return new RecordBatch(0, schema, List.of(rows), List.of());
    }

    private RecordBatch run(RecordBatch input, RQueryStep... steps) {
        return engine.applyStepsToBatch(input, List.of(steps), Map.of(), "t1", "d1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeColumnDropsSchemaAndValues() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("a", DataType.INT64, "b", DataType.UTF8_STRING)),
                row("a", 1L, "b", "x"));
        RecordBatch out = run(in, RQueryStep.removeColumn("Source", "b"));
        assertEquals(List.of("a"), List.copyOf(out.schema().keySet()));
        assertTrue(!out.rows().getFirst().containsKey("b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filterNullsRemovesBlankAndNullRows() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("a", DataType.UTF8_STRING)),
                row("a", "x"), row("a", null), row("a", " "));
        RecordBatch out = run(in, new RQueryStep(
                "FilterNullsStep", "Source", null, null, "a", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, out.rowCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void filterRowsSupportsCompoundRangePredicate() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("d", DataType.ISO_DATE)),
                row("d", "2025-01-10"), row("d", "2025-03-01"), row("d", "2025-06-01"));
        RecordBatch out = run(in, RQueryStep.filterRows("Source", "[d] >= '2025-01-01' and [d] < '2025-04-01'"));
        assertEquals(2, out.rowCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fillDownPropagatesLastNonNullValue() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("k", DataType.UTF8_STRING)),
                row("k", "A"), row("k", null), row("k", "B"), row("k", ""));
        RecordBatch out = run(in, RQueryStep.fillDown("Source", List.of("k")));
        assertEquals("A", out.rows().get(1).get("k"));
        assertEquals("B", out.rows().get(3).get("k"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeTopAndBottomRows() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("a", DataType.INT64)),
                row("a", 1L), row("a", 2L), row("a", 3L), row("a", 4L));
        RecordBatch out = run(in, RQueryStep.removeTopRows("Source", 1), RQueryStep.removeBottomRows("Source", 1));
        assertEquals(2, out.rowCount());
        assertEquals(2L, out.rows().getFirst().get("a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void promoteHeadersUsesFirstRowAsColumnNames() {
        Map<String, DataType> schema = new LinkedHashMap<>();
        schema.put("Column1", DataType.UTF8_STRING);
        schema.put("Column2", DataType.UTF8_STRING);
        RecordBatch in = batch(schema,
                row("Column1", "id", "Column2", "amount"),
                row("Column1", "1", "Column2", "10.5"));
        RecordBatch out = run(in, RQueryStep.promoteHeaders("Source"));
        assertEquals(List.of("id", "amount"), List.copyOf(out.schema().keySet()));
        assertEquals(1, out.rowCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void transposeSwapsRowsAndColumns() {
        Map<String, DataType> schema = new LinkedHashMap<>();
        schema.put("a", DataType.UTF8_STRING);
        schema.put("b", DataType.UTF8_STRING);
        RecordBatch in = batch(schema, row("a", "1", "b", "2"), row("a", "3", "b", "4"));
        RecordBatch out = run(in, RQueryStep.transpose("Source"));
        assertEquals(2, out.rowCount());
        assertEquals("a", out.rows().get(0).get("Column1"));
        assertEquals("3", out.rows().get(0).get("Column3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitColumnByDelimiterCreatesIndexedColumns() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("attr", DataType.UTF8_STRING)),
                row("attr", "Q1:2025"));
        RecordBatch out = run(in, RQueryStep.splitColumnByDelimiter("Source", "attr", ":"));
        assertEquals("Q1", out.rows().getFirst().get("attr.1"));
        assertEquals("2025", out.rows().getFirst().get("attr.2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeDuplicatesAndAddIndexColumn() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("c", DataType.UTF8_STRING)),
                row("c", "x"), row("c", "x"), row("c", "y"));
        RecordBatch out = run(in,
                RQueryStep.removeDuplicates("Source", List.of("c")),
                RQueryStep.addIndexColumn("Source", "client id", 1, 1));
        assertEquals(2, out.rowCount());
        assertEquals(1L, out.rows().get(0).get("client id"));
        assertEquals(2L, out.rows().get(1).get("client id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transformColumnNamesAddsSuffixAndReplacesUnderscore() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("first_name", DataType.UTF8_STRING)),
                row("first_name", "A"));
        RecordBatch out = run(in,
                RQueryStep.transformColumnNames("Source", "REPLACE_TEXT", "_", " "),
                RQueryStep.transformColumnNames("Source", "ADD_SUFFIX", " Boston", null));
        assertTrue(out.schema().containsKey("first name Boston"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customColumnSupportsTextSelectConditionalAndConcat() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("phone", DataType.UTF8_STRING, "kind", DataType.UTF8_STRING)),
                row("phone", "06.12-34 56", "kind", "mobile"));
        RecordBatch out = run(in,
                RQueryStep.addCustomColumn("Source", "digits", DataType.UTF8_STRING,
                        "Text.Select([phone], {\"0\"..\"9\"})", null, "quarantine"),
                RQueryStep.addCustomColumn("Source", "label", DataType.UTF8_STRING,
                        "[kind] & \"-\" & [digits]", null, "quarantine"),
                RQueryStep.addCustomColumn("Source", "flag", DataType.UTF8_STRING,
                        "if [kind] == \"mobile\" then [digits] else null", null, "quarantine"));
        Map<String, Object> r = out.rows().getFirst();
        assertEquals("06123456", r.get("digits"));
        assertEquals("mobile-06123456", r.get("label"));
        assertEquals("06123456", r.get("flag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeTypeQuarantinesInvalidRowsWithoutHaltingPipeline() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("n", DataType.UTF8_STRING)),
                row("n", "12"), row("n", "oops"));
        RecordBatch out = run(in, RQueryStep.changeType("Source", "n", DataType.INT64));
        assertEquals(1, out.rowCount());
        assertEquals(1, out.quarantineCount());
        assertNull(out.quarantinedRecords().getFirst().getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupRowsAggregatesByKey() {
        RecordBatch in = batch(
                new LinkedHashMap<>(Map.of("k", DataType.UTF8_STRING, "v", DataType.FLOAT64)),
                row("k", "a", "v", 1.0), row("k", "a", "v", 2.0), row("k", "b", "v", 5.0));
        RecordBatch out = run(in, RQueryStep.groupRows("Source", List.of("k"),
                List.of(new RQueryStep.AggregateDef("total", "v", RQueryStep.AggregationFunction.SUM))));
        assertEquals(2, out.rowCount());
        assertEquals(3.0, (double) out.rows().get(0).get("total"));
    }
}
