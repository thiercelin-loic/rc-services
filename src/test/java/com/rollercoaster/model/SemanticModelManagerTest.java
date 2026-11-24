package com.rollercoaster.model;

import com.rollercoaster.model.SemanticModelManager.ColumnMetadataUpdate;
import com.rollercoaster.model.SemanticModelManager.ColumnTarget;
import com.rollercoaster.model.SemanticModelManager.DateTableSpec;
import com.rollercoaster.model.SemanticModelManager.ModelMutationResult;
import com.rollercoaster.model.SemanticModelManager.RelationshipRoutingUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticModelManagerTest {

    private SemanticModelManager manager;
    private UUID modelId;
    private SemanticModelSchema baseSchema;

    @BeforeEach
    void setUp() {
        manager = new SemanticModelManager(new RelationshipInferenceService(), new RsdlFormatter());
        modelId = UUID.randomUUID();

        TableDefinition eventsTable = new TableDefinition(
                "events", "FACT", null, 1000L, false,
                List.of(
                        new ColumnDefinition("event_id", "INT64", true, false),
                        new ColumnDefinition("device_id", "VARCHAR", false, false),
                        new ColumnDefinition("backup_device_id", "VARCHAR", false, false),
                        new ColumnDefinition("event_date", "DATE", false, false)
                ));
        TableDefinition devicesTable = new TableDefinition(
                "devices", "DIMENSION", null, 50L, false,
                List.of(
                        new ColumnDefinition("device_id", "VARCHAR", true, false),
                        new ColumnDefinition("device_name", "VARCHAR", false, false)
                ));
        TableDefinition calendarTable = new TableDefinition(
                "calendar", "DIMENSION", null, 365L, false,
                List.of(
                        new ColumnDefinition("calendar_date", "DATE", true, false),
                        new ColumnDefinition("month_name", "VARCHAR", false, false)
                ));

        baseSchema = new SemanticModelSchema(
                modelId,
                "Operations Semantic Model",
                1,
                List.of(eventsTable, devicesTable, calendarTable),
                List.of(),
                List.of(),
                Map.of(),
                false);
        manager.registerModel(baseSchema);
    }

    @Test
    void createUpdateAndDeleteRelationshipMutateModelAndRecomputeDagAndRsdl() {
        RelationshipDefinition rel = new RelationshipDefinition(
                "rel_events_devices", "events", "device_id", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, true);

        ModelMutationResult created = manager.createRelationship(modelId, rel);
        assertTrue(created.dagValidation().valid());
        assertEquals(1, created.schema().relationships().size());
        assertTrue(created.rsdl().contains("relationship 'rel_events_devices'"));
        assertTrue(created.rsdl().contains("column 'device_id': VARCHAR [FK]"));

        assertThrows(IllegalArgumentException.class, () -> manager.createRelationship(modelId, rel));

        RelationshipDefinition updatedRel = new RelationshipDefinition(
                "rel_events_devices", "events", "backup_device_id", "devices", "device_id",
                Cardinality.ONE_TO_ONE, CrossFilterDirection.BOTH, true);
        ModelMutationResult updated = manager.updateRelationship(modelId, "rel_events_devices", updatedRel);
        assertEquals("backup_device_id", updated.schema().relationships().get(0).fromColumn());
        assertTrue(updated.rsdl().contains("cardinality: OneToOne"));
        assertTrue(updated.rsdl().contains("crossFilter: Both"));

        ModelMutationResult deleted = manager.deleteRelationship(modelId, "rel_events_devices");
        assertTrue(deleted.schema().relationships().isEmpty());
        assertFalse(deleted.rsdl().contains("relationship 'rel_events_devices'"));
        assertThrows(IllegalArgumentException.class, () -> manager.deleteRelationship(modelId, "rel_events_devices"));
    }

    @Test
    void toggleActiveRelationshipAndUpdateRoutingDetectAmbiguousPathsWhenBothActive() {
        RelationshipDefinition primaryRel = new RelationshipDefinition(
                "rel_primary_device", "events", "device_id", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, true);
        RelationshipDefinition secondaryRel = new RelationshipDefinition(
                "rel_backup_device", "events", "backup_device_id", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, false);

        manager.createRelationship(modelId, primaryRel);
        ModelMutationResult withInactiveSecondary = manager.createRelationship(modelId, secondaryRel);
        assertTrue(withInactiveSecondary.dagValidation().valid());

        ModelMutationResult activatedBoth = manager.toggleActiveRelationship(modelId, "rel_backup_device");
        assertFalse(activatedBoth.dagValidation().valid());
        assertTrue(activatedBoth.dagValidation().hasAmbiguousActivePaths());

        ModelMutationResult deactivatedAgain = manager.toggleActiveRelationship(modelId, "rel_backup_device", false);
        assertTrue(deactivatedAgain.dagValidation().valid());

        ModelMutationResult routed = manager.updateCardinalityAndCrossFilter(
                modelId,
                new RelationshipRoutingUpdate("rel_primary_device", Cardinality.MANY_TO_MANY, CrossFilterDirection.BOTH));
        assertEquals(Cardinality.MANY_TO_MANY, routed.schema().relationships().get(0).cardinality());
        assertEquals(CrossFilterDirection.BOTH, routed.schema().relationships().get(0).crossFilterDirection());
        assertTrue(routed.rsdl().contains("cardinality: ManyToMany"));
        assertTrue(routed.rsdl().contains("crossFilter: Both"));
    }

    @Test
    void togglePrimaryKeyHideAllForeignKeysMarkDateTableAndToggleDiscourageImplicitMeasures() {
        RelationshipDefinition rel = new RelationshipDefinition(
                "rel_events_devices", "events", "device_id", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, true);
        manager.createRelationship(modelId, rel);

        ModelMutationResult hiddenFks = manager.hideAllForeignKeys(modelId);
        ColumnDefinition fkCol = hiddenFks.schema().tables().stream()
                .filter(t -> t.name().equals("events"))
                .findFirst().orElseThrow()
                .columns().stream()
                .filter(c -> c.name().equals("device_id"))
                .findFirst().orElseThrow();
        assertTrue(fkCol.isForeignKey());
        assertTrue(fkCol.isHidden());
        assertTrue(hiddenFks.rsdl().contains("column 'device_id': VARCHAR [FK] [Hidden]"));

        ModelMutationResult pkToggled = manager.togglePrimaryKey(modelId, "devices", "device_name");
        ColumnDefinition deviceNameCol = pkToggled.schema().tables().stream()
                .filter(t -> t.name().equals("devices"))
                .findFirst().orElseThrow()
                .columns().stream()
                .filter(c -> c.name().equals("device_name"))
                .findFirst().orElseThrow();
        assertTrue(deviceNameCol.isPrimaryKey());

        ModelMutationResult dateMarked = manager.markDateTable(modelId, "calendar", "");
        TableDefinition markedCalendar = dateMarked.schema().tables().stream()
                .filter(t -> t.name().equals("calendar"))
                .findFirst().orElseThrow();
        assertTrue(markedCalendar.isDateTable());
        assertEquals("calendar_date", markedCalendar.dateColumn());
        assertTrue(dateMarked.rsdl().contains("isDateTable: true"));

        ModelMutationResult switchedDateTable = manager.markDateTable(
                modelId, new DateTableSpec("events", true, "event_date"));
        TableDefinition unmarkedCalendar = switchedDateTable.schema().tables().stream()
                .filter(t -> t.name().equals("calendar"))
                .findFirst().orElseThrow();
        assertFalse(unmarkedCalendar.isDateTable());

        assertThrows(IllegalArgumentException.class, () -> manager.markDateTable(modelId, "devices", ""));

        ModelMutationResult implicitToggled = manager.toggleDiscourageImplicitMeasures(modelId);
        assertTrue(implicitToggled.schema().discourageImplicitMeasures());
        assertTrue(implicitToggled.rsdl().contains("discourageImplicitMeasures: true"));
    }

    @Test
    void failFastValidationRejectsInvalidTablesColumnsAndSelfRelationships() {
        RelationshipDefinition selfJoin = new RelationshipDefinition(
                "rel_self", "events", "event_id", "events", "event_id",
                Cardinality.ONE_TO_ONE, CrossFilterDirection.SINGLE, true);
        assertThrows(IllegalArgumentException.class, () -> manager.createRelationship(modelId, selfJoin));

        RelationshipDefinition missingTable = new RelationshipDefinition(
                "rel_missing", "unknown_table", "event_id", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, true);
        assertThrows(IllegalArgumentException.class, () -> manager.createRelationship(modelId, missingTable));

        RelationshipDefinition missingColumn = new RelationshipDefinition(
                "rel_missing_col", "events", "non_existent", "devices", "device_id",
                Cardinality.MANY_TO_ONE, CrossFilterDirection.SINGLE, true);
        assertThrows(IllegalArgumentException.class, () -> manager.createRelationship(modelId, missingColumn));

        assertThrows(IllegalArgumentException.class, () -> manager.togglePrimaryKey(modelId, "events", "missing_col"));
    }

    @Test
    void columnMetadataUpdatesAndUpsertsValidateAndMutateSchema() {
        ColumnTarget target = new ColumnTarget("calendar", "month_name");
        ModelMutationResult updated = manager.updateColumnMetadata(
                modelId,
                target,
                new ColumnMetadataUpdate("VARCHAR", "None", "calendar_date", true));

        ColumnDefinition monthCol = updated.schema().tables().stream()
                .filter(t -> t.name().equals("calendar"))
                .findFirst().orElseThrow()
                .columns().stream()
                .filter(c -> c.name().equals("month_name"))
                .findFirst().orElseThrow();
        assertEquals("VARCHAR", monthCol.dataType());
        assertEquals("None", monthCol.summarizeBy());
        assertEquals("calendar_date", monthCol.sortByColumn());
        assertTrue(monthCol.isHidden());

        manager.updateColumnDataType(modelId, target, "TEXT");
        manager.updateColumnSummarization(modelId, target, "Count");
        manager.updateColumnSortBy(modelId, target, "");
        manager.updateColumnFormat(modelId, target, "Percentage");
        ModelMutationResult unhidden = manager.updateColumnHidden(modelId, target, false);
        ColumnDefinition afterConvenience = unhidden.schema().tables().stream()
                .filter(t -> t.name().equals("calendar"))
                .findFirst().orElseThrow()
                .columns().stream()
                .filter(c -> c.name().equals("month_name"))
                .findFirst().orElseThrow();
        assertEquals("TEXT", afterConvenience.dataType());
        assertEquals("Count", afterConvenience.summarizeBy());
        assertEquals("", afterConvenience.sortByColumn());
        assertEquals("Percentage", afterConvenience.format());
        assertFalse(afterConvenience.isHidden());

        ColumnDefinition calcCol = new ColumnDefinition("year_num", "INT", false, false);
        ModelMutationResult upsertedCol = manager.upsertColumn(modelId, "calendar", calcCol);
        assertEquals(3, upsertedCol.schema().tables().stream()
                .filter(t -> t.name().equals("calendar")).findFirst().orElseThrow().columns().size());

        TableDefinition newTable = new TableDefinition(
                "summary_metrics", "FACT", null, 10L, false,
                List.of(new ColumnDefinition("metric_key", "VARCHAR", true, false)));
        ModelMutationResult upsertedTable = manager.upsertTable(modelId, newTable);
        assertEquals(4, upsertedTable.schema().tables().size());

        assertThrows(IllegalArgumentException.class,
                () -> manager.updateColumnSortBy(modelId, target, "month_name"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateColumnSortBy(modelId, target, "non_existent"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateColumnSummarization(modelId, target, "Median"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateColumnDataType(modelId, target, "   "));
    }
}
