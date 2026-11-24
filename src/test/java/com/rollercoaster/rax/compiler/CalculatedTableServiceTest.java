package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.DuckDbConnectionManager;
import com.rollercoaster.model.SemanticModelManager;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableRequest;
import com.rollercoaster.rax.compiler.CalculatedTableService.CalculatedTableResult;
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

class CalculatedTableServiceTest {

    private DuckDbConnectionManager duckDbConnectionManager;
    private SemanticModelManager semanticModelManager;
    private CalculatedTableService service;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        duckDbConnectionManager = new DuckDbConnectionManager("1GB", 2);
        duckDbConnectionManager.initializeEngine();
        semanticModelManager = new SemanticModelManager();
        service = new CalculatedTableService(
                semanticModelManager, new InfoFunctionEvaluator(), duckDbConnectionManager);

        modelId = UUID.randomUUID();
        TableDefinition events = new TableDefinition(
                "events",
                "FACT",
                null,
                2L,
                false,
                List.of(
                        new ColumnDefinition("event_date", "DATE", false, false),
                        new ColumnDefinition("category", "VARCHAR", false, false),
                        new ColumnDefinition("score", "DECIMAL", false, false)));

        semanticModelManager.registerModel(new SemanticModelSchema(
                modelId, "Analytics Workspace", 1,
                List.of(events), List.of(), List.of(), Map.of(), false));

        duckDbConnectionManager.ingestTableRows("events", List.of(
                Map.of("event_date", "2025-03-10", "category", "Alpha", "score", 10.0),
                Map.of("event_date", "2025-11-20", "category", "Alpha", "score", 20.0),
                Map.of("event_date", "2026-02-05", "category", "Beta", "score", 15.0)));
    }

    @AfterEach
    void tearDown() {
        duckDbConnectionManager.shutdownEngine();
    }

    @Test
    @DisplayName("Builds CALENDAR date table with leap year coverage, metadata flags, and optional offsets")
    void testBuildCalendarTableAndOffsets() {
        CalculatedTableResult standard = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId, "Calendar", "CALENDAR(DATE(2024, 1, 1), DATE(2024, 12, 31))"));

        assertThat(standard.tableName()).isEqualTo("Calendar");
        assertThat(standard.isDateTable()).isTrue();
        assertThat(standard.dateColumn()).isEqualTo("Date");
        assertThat(standard.rows()).hasSize(366);
        assertThat(standard.columns())
                .extracting(ColumnDefinition::name)
                .containsExactly("Date", "Year", "Quarter", "Month", "MonthNum", "YearMonth", "Day");

        ColumnDefinition monthCol = standard.columns().stream()
                .filter(c -> c.name().equals("Month"))
                .findFirst().orElseThrow();
        ColumnDefinition monthNumCol = standard.columns().stream()
                .filter(c -> c.name().equals("MonthNum"))
                .findFirst().orElseThrow();
        assertThat(monthCol.sortByColumn()).isEqualTo("MonthNum");
        assertThat(monthNumCol.isHidden()).isTrue();

        CalculatedTableResult withOffsets = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId, "CalendarOffsets", "CALENDAR(DATE(2026, 1, 1), DATE(2026, 1, 5)) /* OFFSET */"));
        assertThat(withOffsets.rows()).hasSize(5);
        assertThat(withOffsets.columns())
                .extracting(ColumnDefinition::name)
                .contains("Offset Day", "Offset Month", "Offset Year");
        assertThat(withOffsets.rows().get(0)).containsKeys("Offset Day", "Offset Month", "Offset Year");

        TableDefinition persisted = semanticModelManager.getModel(modelId).tables().stream()
                .filter(t -> t.name().equals("Calendar"))
                .findFirst().orElseThrow();
        assertThat(persisted.isDateTable()).isTrue();
        assertThat(persisted.role()).isEqualTo("DIMENSION");
    }

    @Test
    @DisplayName("Builds CALENDARAUTO spanning model dates and respects fiscalYearEndMonth")
    void testCalendarAutoStandardAndFiscal() {
        CalculatedTableResult autoStandard = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId, "AutoCal", "CALENDARAUTO()"));

        assertThat(autoStandard.isDateTable()).isTrue();
        assertThat(autoStandard.rows()).hasSize(730);
        assertThat(autoStandard.rows().get(0).get("Date")).isEqualTo("2025-01-01");
        assertThat(autoStandard.rows().get(autoStandard.rows().size() - 1).get("Date")).isEqualTo("2026-12-31");

        CalculatedTableResult fiscalCal = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId, "FiscalCal", "CALENDARAUTO(6)"));
        assertThat(fiscalCal.rows().get(0).get("Date")).isEqualTo("2024-07-01");
        assertThat(fiscalCal.rows().get(fiscalCal.rows().size() - 1).get("Date")).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("Builds SUMMARIZECOLUMNS aggregated table and INFO.VIEW data dictionary table")
    void testSummarizeColumnsAndDataDictionary() {
        CalculatedTableResult summary = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId,
                "CategorySummary",
                "SUMMARIZECOLUMNS(events[category], \"TotalScore\", SUM(events[score]))"));

        assertThat(summary.isDateTable()).isFalse();
        assertThat(summary.columns()).extracting(ColumnDefinition::name).containsExactly("category", "TotalScore");
        assertThat(summary.rows()).hasSize(2);
        assertThat(summary.rows().get(0)).containsEntry("category", "Alpha").containsEntry("TotalScore", 30.0);
        assertThat(summary.rows().get(1)).containsEntry("category", "Beta").containsEntry("TotalScore", 15.0);

        CalculatedTableResult dictionary = service.buildCalculatedTableFromFormula(new CalculatedTableRequest(
                modelId, "ModelCatalog", "INFO.VIEW.COLUMNS()"));
        assertThat(dictionary.isDateTable()).isFalse();
        assertThat(dictionary.columns())
                .extracting(ColumnDefinition::name)
                .containsExactly("Type", "Name", "Table", "Description", "Rax");
        assertThat(dictionary.rows()).isNotEmpty();
    }

    @Test
    @DisplayName("Caps calendar range at 4 years and validates invalid date ranges and fiscal months")
    void testCalendarRangeGuardrailsAndValidation() {
        List<Map<String, Object>> capped = service.generateCalendarRowsRange(
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31), false);
        assertThat(capped).hasSize(1461);

        assertThatThrownBy(() -> service.generateCalendarRowsRange(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildCalculatedTableFromFormula(
                new CalculatedTableRequest(modelId, "BadFiscal", "CALENDARAUTO(13)")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildCalculatedTableFromFormula(
                new CalculatedTableRequest(modelId, "Unknown", "UNKNOWN_TABLE_EXPR()")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
