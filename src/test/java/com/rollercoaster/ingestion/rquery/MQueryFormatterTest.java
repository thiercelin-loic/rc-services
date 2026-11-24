package com.rollercoaster.ingestion.rquery;

import com.rollercoaster.ingestion.DataType;
import com.rollercoaster.ingestion.SourceFormat;
import com.rollercoaster.ingestion.rquery.MQueryFormatter.MQueryFormatOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MQueryFormatterTest {

    private final MQueryFormatter formatter = new MQueryFormatter();

    @Test
    void formatScriptGeneratesLetInBlockForFourStepInitialPipeline() {
        RQueryStep fileSource = new RQueryStep(
                "FileSource", null, "Orders_2025.xlsx", SourceFormat.XLSX, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        RQueryStep navigation = new RQueryStep(
                "Navigation", "FileSource", null, null, "Orders", null,
                null, null, null, null, null, null, null, null, null, "Orders", null, null, null, null);
        RQueryStep promoteHeaders = RQueryStep.promoteHeaders("Navigation");
        RQueryStep changeType = RQueryStep.changeType("PromoteHeadersStep", "Amount", DataType.CURRENCY);

        String script = formatter.formatScript(List.of(fileSource, navigation, promoteHeaders, changeType));

        assertTrue(script.startsWith("let\n"));
        assertTrue(script.contains("Source = Excel.Workbook(File.Contents(\"Orders_2025.xlsx\"), null, true)"));
        assertTrue(script.contains("Navigation = Source{[Item=\"Orders\",Kind=\"Sheet\"]}[Data]"));
        assertTrue(script.contains("#\"Promoted Headers\" = Table.PromoteHeaders(Navigation, [PromoteAllScalars=true])"));
        assertTrue(script.contains("#\"Changed Type\" = Table.TransformColumnTypes(#\"Promoted Headers\", {{\"Amount\", Currency.Type}})"));
        assertTrue(script.endsWith("in\n    #\"Changed Type\""));
    }

    @Test
    void formatScriptIncludesOptionalRangeStartAndRangeEndParameters() {
        RQueryStep filterStep = RQueryStep.filterRows("Source", "[OrderDate] >= '2025-01-01'");
        MQueryFormatOptions options = new MQueryFormatOptions("Orders", "2025-01-01", "2025-12-31");

        String script = formatter.formatScript(List.of(filterStep), options);

        assertTrue(script.contains("RangeStart = \"2025-01-01\""));
        assertTrue(script.contains("RangeEnd = \"2025-12-31\""));
        assertTrue(script.contains("#\"Filtered Rows\" = Table.SelectRows(Source, each [OrderDate] >= '2025-01-01')"));
    }

    @Test
    void formatStepFormulaHandlesExhaustiveStepTypes() {
        assertEquals(
                "= Table.RenameColumns(Source, {{\"old_col\", \"new_col\"}})",
                formatter.formatStepFormula(RQueryStep.renameColumn("Source", "old_col", "new_col"), "Source"));

        assertEquals(
                "= Table.RemoveColumns(#\"Changed Type\", {\"temp\"})",
                formatter.formatStepFormula(RQueryStep.removeColumn("Changed Type", "temp"), "Changed Type"));

        assertEquals(
                "= Table.AddColumn(Source, \"Margin\", each [Revenue] - [Cost], type number)",
                formatter.formatStepFormula(
                        RQueryStep.addCustomColumn("Source", "Margin", DataType.FLOAT64, "[Revenue] - [Cost]", Map.of(), "quarantine"),
                        "Source"));

        assertEquals(
                "= Table.Group(Source, {\"Category\"}, {{\"Total\", each List.Sum([Sales]), type number}})",
                formatter.formatStepFormula(
                        RQueryStep.groupRows("Source", List.of("Category"),
                                List.of(new RQueryStep.AggregateDef("Total", "Sales", RQueryStep.AggregationFunction.SUM))),
                        "Source"));

        assertEquals(
                "= Table.Skip(Source, 3)",
                formatter.formatStepFormula(RQueryStep.removeTopRows("Source", 3), "Source"));
    }

    @Test
    void formatEmptyPipelineProducesFallbackTableFromRecords() {
        String script = formatter.formatScript(List.of());
        assertTrue(script.contains("Source = Table.FromRecords({})"));
        assertEquals("= Table.FromRecords({})", formatter.formatStepFormula(null));
    }

    @Test
    void formatScriptDisambiguatesRepeatedStepsAndMatchesLastFormula() {
        List<RQueryStep> steps = List.of(
                RQueryStep.promoteHeaders("Navigation"),
                RQueryStep.changeType("PromoteHeadersStep", "id", DataType.INT64),
                RQueryStep.changeType("ChangeTypeStep", "amount", DataType.FLOAT64),
                RQueryStep.filterRows("ChangeTypeStep", "[amount] > 100")
        );

        String script = formatter.formatScript(steps);
        assertTrue(script.contains("#\"Changed Type\" = Table.TransformColumnTypes(#\"Promoted Headers\", {{\"id\", Int64.Type}})"));
        assertTrue(script.contains("#\"Changed Type1\" = Table.TransformColumnTypes(#\"Changed Type\", {{\"amount\", type number}})"));
        assertTrue(script.contains("#\"Filtered Rows\" = Table.SelectRows(#\"Changed Type1\", each [amount] > 100)"));
        assertEquals("= Table.SelectRows(#\"Changed Type1\", each [amount] > 100)", formatter.formatLastStepFormula(steps));
    }
}
