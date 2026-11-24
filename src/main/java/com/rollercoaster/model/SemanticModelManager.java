package com.rollercoaster.model;

import com.rollercoaster.rax.diagnostics.DiagnosticMarker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP-agnostic domain service managing Semantic Model schemas, relationship mutations,
 * DAG consistency validation, SCD Type 2 boundary injection, Role-Playing dimension aliasing,
 * and RSDL/TMDL delegation.
 */
@Service
public class SemanticModelManager {

    private final RelationshipInferenceService relationshipInferenceService;
    private final RsdlFormatter rsdlFormatter;
    private final Map<UUID, SemanticModelSchema> registeredModels = new ConcurrentHashMap<>();
    private final SemanticModelSchema defaultModel;

    public record ModelMutationResult(SemanticModelSchema schema, DagValidationReport dagValidation, String rsdl) {
        public ModelMutationResult {
            Objects.requireNonNull(schema, "schema must not be null");
            Objects.requireNonNull(dagValidation, "dagValidation must not be null");
            rsdl = rsdl != null ? rsdl : "";
        }
    }

    public record RelationshipRoutingUpdate(
            String relationshipName,
            Cardinality cardinality,
            CrossFilterDirection crossFilterDirection
    ) {
        public RelationshipRoutingUpdate {
            requireNonBlank(relationshipName, "relationshipName");
            Objects.requireNonNull(cardinality, "cardinality must not be null");
            Objects.requireNonNull(crossFilterDirection, "crossFilterDirection must not be null");
        }
    }

    public record ColumnTarget(String tableName, String columnName) {
        public ColumnTarget {
            requireNonBlank(tableName, "tableName");
            requireNonBlank(columnName, "columnName");
        }
    }

    public record DateTableSpec(String tableName, boolean dateTable, String dateColumn) {
        public DateTableSpec {
            requireNonBlank(tableName, "tableName");
            dateColumn = dateColumn != null ? dateColumn.trim() : "";
        }
    }

    public enum SummarizationMode {
        DEFAULT("default"),
        NONE("None"),
        SUM("Sum"),
        AVERAGE("Average"),
        MIN("Min"),
        MAX("Max"),
        COUNT("Count"),
        DISTINCT_COUNT("DistinctCount");

        private final String label;

        SummarizationMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static SummarizationMode fromToken(String token) {
            requireNonBlank(token, "summarizeBy");
            return switch (token.trim().toLowerCase(Locale.ROOT)) {
                case "default" -> DEFAULT;
                case "none", "do not summarize" -> NONE;
                case "sum" -> SUM;
                case "average", "avg" -> AVERAGE;
                case "min" -> MIN;
                case "max" -> MAX;
                case "count" -> COUNT;
                case "distinctcount", "distinct_count", "distinct count" -> DISTINCT_COUNT;
                default -> throw new IllegalArgumentException("Unsupported summarizeBy mode: " + token);
            };
        }
    }

    public record ColumnMetadataUpdate(
            String dataType,
            String summarizeBy,
            String sortByColumn,
            Boolean isHidden,
            String format
    ) {
        public ColumnMetadataUpdate {
            if (dataType != null && dataType.isBlank()) {
                throw new IllegalArgumentException("dataType must not be blank when provided.");
            }
            dataType = dataType != null ? dataType.trim() : null;
            summarizeBy = summarizeBy != null ? SummarizationMode.fromToken(summarizeBy).label() : null;
            sortByColumn = sortByColumn != null ? sortByColumn.trim() : null;
            format = format != null ? format.trim() : null;
        }

        public ColumnMetadataUpdate(
                String dataType,
                String summarizeBy,
                String sortByColumn,
                Boolean isHidden) {
            this(dataType, summarizeBy, sortByColumn, isHidden, null);
        }
    }

    public record RolePlayingJoinRequest(
            String factTable,
            String dimensionTable,
            Map<String, String> activeRelationshipOverrides
    ) {}

    private record ColumnFlags(boolean primaryKey, boolean foreignKey, boolean hidden) {}

    public SemanticModelManager() {
        this(new RelationshipInferenceService(), new RsdlFormatter());
    }

    @Autowired
    public SemanticModelManager(
            RelationshipInferenceService relationshipInferenceService,
            RsdlFormatter rsdlFormatter) {
        this.relationshipInferenceService = Objects.requireNonNull(
                relationshipInferenceService, "relationshipInferenceService must not be null");
        this.rsdlFormatter = Objects.requireNonNull(rsdlFormatter, "rsdlFormatter must not be null");
        this.defaultModel = new SemanticModelSchema(
                UUID.fromString("a98f12b4-539c-4b6e-8210-90c1f6d90124"),
                "Untitled Project", 1, List.of(), List.of(), List.of(), Map.of(), false);
        this.registeredModels.put(this.defaultModel.modelId(), this.defaultModel);
    }

    public SemanticModelSchema getDefaultRetailModel() {
        return defaultModel;
    }

    public SemanticModelSchema getDefaultModel() {
        return defaultModel;
    }

    public SemanticModelSchema getModel(UUID modelId) {
        return modelId == null ? defaultModel : registeredModels.getOrDefault(modelId, defaultModel);
    }

    public void registerModel(SemanticModelSchema schema) {
        if (schema != null && schema.modelId() != null) {
            registeredModels.put(schema.modelId(), schema);
        }
    }

    public SemanticModelSchema normalizeSchema(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        UUID id = schema.modelId() != null ? schema.modelId() : UUID.randomUUID();
        String name = (schema.name() != null && !schema.name().isBlank()) ? schema.name().trim() : "Normalized Model";
        int fy = (schema.fiscalYearStartMonth() >= 1 && schema.fiscalYearStartMonth() <= 12)
                ? schema.fiscalYearStartMonth()
                : 1;
        return new SemanticModelSchema(
                id, name, fy, schema.tables(), schema.relationships(),
                schema.measures(), schema.rlsRolePredicates(), schema.discourageImplicitMeasures());
    }

    public ModelMutationResult evaluateModel(SemanticModelSchema schema) {
        SemanticModelSchema normalized = normalizeSchema(schema);
        return new ModelMutationResult(
                normalized, validateDagConsistency(normalized), rsdlFormatter.format(normalized));
    }

    public SemanticModelSchema saveModel(SemanticModelSchema schema) {
        return saveAndValidateModel(schema).schema();
    }

    public SemanticModelSchema saveModel(UUID modelId, SemanticModelSchema schema) {
        return saveAndValidateModel(modelId, schema).schema();
    }

    public ModelMutationResult saveAndValidateModel(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "A model payload is required.");
        ModelMutationResult evaluation = evaluateModel(schema);
        if (!evaluation.dagValidation().valid()) {
            throw new IllegalArgumentException(
                    "Semantic model validation failed: " + evaluation.dagValidation().diagnostics());
        }
        registerModel(evaluation.schema());
        return evaluation;
    }

    public ModelMutationResult saveAndValidateModel(UUID modelId, SemanticModelSchema schema) {
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(schema, "A model payload is required.");
        if (schema.modelId() == null || !modelId.equals(schema.modelId())) {
            throw new IllegalArgumentException("The model path and payload identifiers must match.");
        }
        return saveAndValidateModel(schema);
    }

    public ModelMutationResult createRelationship(UUID modelId, RelationshipDefinition relationship) {
        return createRelationship(requireRegisteredModel(modelId), relationship);
    }

    public ModelMutationResult createRelationship(SemanticModelSchema schema, RelationshipDefinition relationship) {
        Objects.requireNonNull(schema, "schema must not be null");
        RelationshipDefinition normalizedRel = validateAndNormalizeRelationship(schema, relationship);
        boolean exists = schema.relationships().stream()
                .anyMatch(r -> r.name().equalsIgnoreCase(normalizedRel.name()));
        if (exists) {
            throw new IllegalArgumentException("Relationship already exists: " + normalizedRel.name());
        }
        List<RelationshipDefinition> updatedRelationships = new ArrayList<>(schema.relationships());
        updatedRelationships.add(normalizedRel);
        List<TableDefinition> updatedTables = markColumnAsForeignKey(
                schema.tables(), new ColumnTarget(normalizedRel.fromTable(), normalizedRel.fromColumn()));
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, updatedRelationships));
    }

    public ModelMutationResult updateRelationship(UUID modelId, RelationshipDefinition updatedRelationship) {
        return updateRelationship(requireRegisteredModel(modelId), updatedRelationship);
    }

    public ModelMutationResult updateRelationship(SemanticModelSchema schema, RelationshipDefinition updated) {
        Objects.requireNonNull(updated, "updatedRelationship must not be null");
        return updateRelationship(schema, updated.name(), updated);
    }

    public ModelMutationResult updateRelationship(UUID modelId, String existingName, RelationshipDefinition updated) {
        return updateRelationship(requireRegisteredModel(modelId), existingName, updated);
    }

    public ModelMutationResult updateRelationship(
            SemanticModelSchema schema,
            String existingName,
            RelationshipDefinition updatedRelationship) {
        Objects.requireNonNull(schema, "schema must not be null");
        requireNonBlank(existingName, "existingName");
        RelationshipDefinition normalizedRel = validateAndNormalizeRelationship(schema, updatedRelationship);
        List<RelationshipDefinition> updatedList = new ArrayList<>(schema.relationships().size());
        boolean found = false;
        for (RelationshipDefinition current : schema.relationships()) {
            if (current.name().equalsIgnoreCase(existingName.trim())) {
                updatedList.add(normalizedRel);
                found = true;
            } else {
                updatedList.add(current);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Relationship not found: " + existingName);
        }
        List<TableDefinition> updatedTables = markColumnAsForeignKey(
                schema.tables(), new ColumnTarget(normalizedRel.fromTable(), normalizedRel.fromColumn()));
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, updatedList));
    }

    public ModelMutationResult deleteRelationship(UUID modelId, String relationshipName) {
        return deleteRelationship(requireRegisteredModel(modelId), relationshipName);
    }

    public ModelMutationResult deleteRelationship(SemanticModelSchema schema, String relationshipName) {
        Objects.requireNonNull(schema, "schema must not be null");
        requireNonBlank(relationshipName, "relationshipName");
        List<RelationshipDefinition> remaining = schema.relationships().stream()
                .filter(r -> !r.name().equalsIgnoreCase(relationshipName.trim()))
                .toList();
        if (remaining.size() == schema.relationships().size()) {
            throw new IllegalArgumentException("Relationship not found: " + relationshipName);
        }
        return finalizeMutation(withTablesAndRelationships(schema, schema.tables(), remaining));
    }

    public ModelMutationResult toggleActiveRelationship(UUID modelId, String relationshipName) {
        return toggleActiveRelationship(requireRegisteredModel(modelId), relationshipName);
    }

    public ModelMutationResult toggleActiveRelationship(SemanticModelSchema schema, String relationshipName) {
        RelationshipDefinition existing = findRelationshipOrThrow(schema, relationshipName);
        return toggleActiveRelationship(schema, relationshipName, !existing.isActive());
    }

    public ModelMutationResult toggleActiveRelationship(UUID modelId, String relationshipName, boolean active) {
        return toggleActiveRelationship(requireRegisteredModel(modelId), relationshipName, active);
    }

    public ModelMutationResult toggleActiveRelationship(
            SemanticModelSchema schema,
            String relationshipName,
            boolean active) {
        RelationshipDefinition existing = findRelationshipOrThrow(schema, relationshipName);
        RelationshipDefinition toggled = new RelationshipDefinition(
                existing.name(), existing.fromTable(), existing.fromColumn(),
                existing.toTable(), existing.toColumn(), existing.cardinality(),
                existing.crossFilterDirection(), active, existing.bridgeTable());
        return replaceRelationship(schema, existing.name(), toggled);
    }

    public ModelMutationResult updateCardinalityAndCrossFilter(UUID modelId, RelationshipRoutingUpdate update) {
        return updateCardinalityAndCrossFilter(requireRegisteredModel(modelId), update);
    }

    public ModelMutationResult updateCardinalityAndCrossFilter(
            SemanticModelSchema schema,
            RelationshipRoutingUpdate update) {
        Objects.requireNonNull(update, "update must not be null");
        RelationshipDefinition existing = findRelationshipOrThrow(schema, update.relationshipName());
        RelationshipDefinition modified = new RelationshipDefinition(
                existing.name(), existing.fromTable(), existing.fromColumn(),
                existing.toTable(), existing.toColumn(), update.cardinality(),
                update.crossFilterDirection(), existing.isActive(), existing.bridgeTable());
        return replaceRelationship(schema, existing.name(), modified);
    }

    public ModelMutationResult updateRelationshipCardinality(
            UUID modelId,
            String relationshipName,
            Cardinality cardinality) {
        return updateRelationshipCardinality(requireRegisteredModel(modelId), relationshipName, cardinality);
    }

    public ModelMutationResult updateRelationshipCardinality(
            SemanticModelSchema schema,
            String relationshipName,
            Cardinality cardinality) {
        Objects.requireNonNull(cardinality, "cardinality must not be null");
        RelationshipDefinition existing = findRelationshipOrThrow(schema, relationshipName);
        CrossFilterDirection direction = existing.crossFilterDirection() != null
                ? existing.crossFilterDirection()
                : CrossFilterDirection.SINGLE;
        return updateCardinalityAndCrossFilter(
                schema, new RelationshipRoutingUpdate(existing.name(), cardinality, direction));
    }

    public ModelMutationResult updateRelationshipCrossFilter(
            UUID modelId,
            String relationshipName,
            CrossFilterDirection direction) {
        return updateRelationshipCrossFilter(requireRegisteredModel(modelId), relationshipName, direction);
    }

    public ModelMutationResult updateRelationshipCrossFilter(
            SemanticModelSchema schema,
            String relationshipName,
            CrossFilterDirection direction) {
        Objects.requireNonNull(direction, "direction must not be null");
        RelationshipDefinition existing = findRelationshipOrThrow(schema, relationshipName);
        Cardinality cardinality = existing.cardinality() != null ? existing.cardinality() : Cardinality.MANY_TO_ONE;
        return updateCardinalityAndCrossFilter(
                schema, new RelationshipRoutingUpdate(existing.name(), cardinality, direction));
    }

    public ModelMutationResult togglePrimaryKey(UUID modelId, String tableName, String columnName) {
        return togglePrimaryKey(requireRegisteredModel(modelId), new ColumnTarget(tableName, columnName));
    }

    public ModelMutationResult togglePrimaryKey(SemanticModelSchema schema, String tableName, String columnName) {
        return togglePrimaryKey(schema, new ColumnTarget(tableName, columnName));
    }

    public ModelMutationResult togglePrimaryKey(SemanticModelSchema schema, ColumnTarget target) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(target, "target must not be null");
        TableDefinition table = findTableOrThrow(schema, target.tableName());
        findColumnOrThrow(table, target.columnName());
        List<TableDefinition> updatedTables = schema.tables().stream()
                .map(t -> !t.name().equalsIgnoreCase(table.name())
                        ? t
                        : copyTableWithColumns(t, t.columns().stream()
                                .map(c -> c.name().equalsIgnoreCase(target.columnName().trim())
                                        ? copyColumnWithFlags(
                                                c, new ColumnFlags(!c.isPrimaryKey(), c.isForeignKey(), c.isHidden()))
                                        : c)
                                .toList()))
                .toList();
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult hideAllForeignKeys(UUID modelId) {
        return hideAllForeignKeys(requireRegisteredModel(modelId));
    }

    public ModelMutationResult hideAllForeignKeys(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        Set<String> relationshipFkKeys = new HashSet<>();
        for (RelationshipDefinition rel : schema.relationships()) {
            relationshipFkKeys.add((rel.fromTable() + "::" + rel.fromColumn()).toLowerCase(Locale.ROOT));
        }
        List<TableDefinition> updatedTables = schema.tables().stream()
                .map(t -> copyTableWithColumns(t, t.columns().stream()
                        .map(c -> {
                            String key = (t.name() + "::" + c.name()).toLowerCase(Locale.ROOT);
                            boolean isFk = c.isForeignKey() || relationshipFkKeys.contains(key);
                            return isFk ? copyColumnWithFlags(c, new ColumnFlags(c.isPrimaryKey(), true, true)) : c;
                        })
                        .toList()))
                .toList();
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult markDateTable(UUID modelId, String tableName, String dateColumn) {
        return markDateTable(requireRegisteredModel(modelId), new DateTableSpec(tableName, true, dateColumn));
    }

    public ModelMutationResult markDateTable(SemanticModelSchema schema, String tableName, String dateColumn) {
        return markDateTable(schema, new DateTableSpec(tableName, true, dateColumn));
    }

    public ModelMutationResult markDateTable(UUID modelId, DateTableSpec spec) {
        return markDateTable(requireRegisteredModel(modelId), spec);
    }

    public ModelMutationResult markDateTable(SemanticModelSchema schema, DateTableSpec spec) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        TableDefinition targetTable = findTableOrThrow(schema, spec.tableName());
        String resolvedDateCol = resolveValidatedDateColumn(targetTable, spec);
        List<TableDefinition> updatedTables = new ArrayList<>(schema.tables().size());
        for (TableDefinition t : schema.tables()) {
            if (t.name().equalsIgnoreCase(targetTable.name())) {
                updatedTables.add(applyDateTableColumnSettings(t, spec.dateTable(), resolvedDateCol));
            } else if (spec.dateTable() && t.isDateTable()) {
                updatedTables.add(copyTableWithDateSettings(t, false, ""));
            } else {
                updatedTables.add(t);
            }
        }
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult updateColumnMetadata(
            UUID modelId,
            ColumnTarget target,
            ColumnMetadataUpdate update) {
        return updateColumnMetadata(requireRegisteredModel(modelId), target, update);
    }

    public ModelMutationResult updateColumnMetadata(
            SemanticModelSchema schema,
            ColumnTarget target,
            ColumnMetadataUpdate update) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(update, "update must not be null");
        TableDefinition targetTable = findTableOrThrow(schema, target.tableName());
        ColumnDefinition existingCol = findColumnOrThrow(targetTable, target.columnName());
        String validatedSortBy = validateSortByColumn(targetTable, existingCol.name(), update.sortByColumn());

        ColumnDefinition updatedCol = new ColumnDefinition(
                existingCol.name(),
                update.dataType() != null ? update.dataType() : existingCol.dataType(),
                existingCol.isPrimaryKey(),
                existingCol.isForeignKey(),
                update.isHidden() != null ? update.isHidden() : existingCol.isHidden(),
                existingCol.piiClassification(),
                existingCol.description(),
                existingCol.displayFolder(),
                update.summarizeBy() != null ? update.summarizeBy() : existingCol.summarizeBy(),
                existingCol.expression(),
                validatedSortBy != null ? validatedSortBy : existingCol.sortByColumn(),
                update.format() != null ? update.format() : existingCol.format());

        List<TableDefinition> updatedTables = replaceColumnInTableList(
                schema.tables(), targetTable.name(), updatedCol);
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult updateColumnDataType(UUID modelId, ColumnTarget target, String dataType) {
        requireNonBlank(dataType, "dataType");
        return updateColumnMetadata(modelId, target, new ColumnMetadataUpdate(dataType, null, null, null));
    }

    public ModelMutationResult updateColumnSummarization(UUID modelId, ColumnTarget target, String summarizeBy) {
        requireNonBlank(summarizeBy, "summarizeBy");
        return updateColumnMetadata(modelId, target, new ColumnMetadataUpdate(null, summarizeBy, null, null));
    }

    public ModelMutationResult updateColumnSortBy(UUID modelId, ColumnTarget target, String sortByColumn) {
        String normalized = sortByColumn != null ? sortByColumn : "";
        return updateColumnMetadata(modelId, target, new ColumnMetadataUpdate(null, null, normalized, null));
    }

    public ModelMutationResult updateColumnHidden(UUID modelId, ColumnTarget target, boolean hidden) {
        return updateColumnMetadata(modelId, target, new ColumnMetadataUpdate(null, null, null, hidden));
    }

    public ModelMutationResult updateColumnFormat(UUID modelId, ColumnTarget target, String format) {
        String normalized = format != null ? format : "";
        return updateColumnMetadata(modelId, target, new ColumnMetadataUpdate(null, null, null, null, normalized));
    }

    public ModelMutationResult upsertColumn(UUID modelId, String tableName, ColumnDefinition column) {
        SemanticModelSchema schema = modelId == null ? defaultModel : requireRegisteredModel(modelId);
        return upsertColumn(schema, tableName, column);
    }

    public ModelMutationResult upsertColumn(SemanticModelSchema schema, String tableName, ColumnDefinition column) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(column, "column must not be null");
        requireNonBlank(column.name(), "column.name");
        TableDefinition targetTable = findTableOrThrow(schema, tableName);

        List<ColumnDefinition> nextCols = new ArrayList<>(targetTable.columns().size() + 1);
        boolean replaced = false;
        for (ColumnDefinition existing : targetTable.columns()) {
            if (existing.name().equalsIgnoreCase(column.name().trim())) {
                nextCols.add(column);
                replaced = true;
            } else {
                nextCols.add(existing);
            }
        }
        if (!replaced) {
            nextCols.add(column);
        }

        List<TableDefinition> updatedTables = schema.tables().stream()
                .map(t -> t.name().equalsIgnoreCase(targetTable.name()) ? copyTableWithColumns(t, nextCols) : t)
                .toList();
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult upsertTable(UUID modelId, TableDefinition table) {
        SemanticModelSchema schema = modelId == null ? defaultModel : requireRegisteredModel(modelId);
        return upsertTable(schema, table);
    }

    public ModelMutationResult upsertTable(SemanticModelSchema schema, TableDefinition table) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(table, "table must not be null");
        requireNonBlank(table.name(), "table.name");

        List<TableDefinition> updatedTables = new ArrayList<>(schema.tables().size() + 1);
        boolean replaced = false;
        for (TableDefinition existing : schema.tables()) {
            if (existing.name().equalsIgnoreCase(table.name().trim())) {
                updatedTables.add(table);
                replaced = true;
            } else {
                updatedTables.add(existing);
            }
        }
        if (!replaced) {
            updatedTables.add(table);
        }
        return finalizeMutation(withTablesAndRelationships(schema, updatedTables, schema.relationships()));
    }

    public ModelMutationResult toggleDiscourageImplicitMeasures(UUID modelId) {
        return toggleDiscourageImplicitMeasures(requireRegisteredModel(modelId));
    }

    public ModelMutationResult toggleDiscourageImplicitMeasures(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        return toggleDiscourageImplicitMeasures(schema, !schema.discourageImplicitMeasures());
    }

    public ModelMutationResult toggleDiscourageImplicitMeasures(UUID modelId, boolean discourage) {
        return toggleDiscourageImplicitMeasures(requireRegisteredModel(modelId), discourage);
    }

    public ModelMutationResult toggleDiscourageImplicitMeasures(SemanticModelSchema schema, boolean discourage) {
        Objects.requireNonNull(schema, "schema must not be null");
        return finalizeMutation(new SemanticModelSchema(
                schema.modelId(), schema.name(), schema.fiscalYearStartMonth(),
                schema.tables(), schema.relationships(), schema.measures(),
                schema.rlsRolePredicates(), discourage));
    }

    public DagValidationReport validateDagConsistency(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        List<DiagnosticMarker> diagnostics = new ArrayList<>();
        Map<String, TableDefinition> tableMap = new HashMap<>();
        for (TableDefinition t : schema.tables()) {
            tableMap.put(t.name().toLowerCase(Locale.ROOT), t);
        }
        boolean violatesStar = detectSnowflakeViolations(schema.relationships(), tableMap, diagnostics);
        Map<String, List<String>> adjacency = new HashMap<>();
        boolean hasAmbiguous = detectAmbiguousPaths(schema.relationships(), adjacency, diagnostics);
        boolean hasCycles = detectDirectedCycles(adjacency, diagnostics);
        return new DagValidationReport(
                !hasCycles && !hasAmbiguous && !violatesStar,
                hasCycles, hasAmbiguous, violatesStar, List.copyOf(diagnostics));
    }

    public String injectScdType2BoundaryFilter(String baseSql, SemanticModelSchema schema, LocalDate evalDate) {
        if (baseSql == null || schema == null) {
            return baseSql;
        }
        String effectiveDate = (evalDate != null ? evalDate : LocalDate.of(2026, 10, 12)).toString();
        String resultSql = baseSql;
        for (TableDefinition table : schema.tables()) {
            if (table.scdType2() && resultSql.toLowerCase(Locale.ROOT).contains(table.name().toLowerCase(Locale.ROOT))) {
                String scdFilter = "'" + effectiveDate + "' BETWEEN " + table.name() + ".valid_from AND "
                        + table.name() + ".valid_to";
                resultSql = resultSql.toUpperCase(Locale.ROOT).contains("WHERE ")
                        ? resultSql + " AND (" + scdFilter + ")"
                        : resultSql + " WHERE (" + scdFilter + ")";
            }
        }
        return resultSql;
    }

    public String resolveRolePlayingJoinClause(SemanticModelSchema schema, RolePlayingJoinRequest request) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Map<String, String> overrides = request.activeRelationshipOverrides();
        for (RelationshipDefinition rel : schema.relationships()) {
            if (rel.fromTable().equalsIgnoreCase(request.factTable())
                    && rel.toTable().equalsIgnoreCase(request.dimensionTable())) {
                String qualifiedFrom = (rel.fromTable() + "[" + rel.fromColumn() + "]")
                        .toLowerCase(Locale.ROOT).replace("'", "");
                if (overrides != null && overrides.containsKey(qualifiedFrom)) {
                    String alias = request.dimensionTable() + "_by_" + rel.fromColumn();
                    return "JOIN " + request.dimensionTable() + " AS " + alias
                            + " ON " + request.factTable() + "." + rel.fromColumn() + " = " + alias + "." + rel.toColumn();
                }
            }
        }
        for (RelationshipDefinition rel : schema.relationships()) {
            if (rel.isActive()
                    && rel.fromTable().equalsIgnoreCase(request.factTable())
                    && rel.toTable().equalsIgnoreCase(request.dimensionTable())) {
                return "JOIN " + request.dimensionTable()
                        + " ON " + request.factTable() + "." + rel.fromColumn() + " = "
                        + request.dimensionTable() + "." + rel.toColumn();
            }
        }
        return "";
    }

    public String formatToRsdl(SemanticModelSchema schema) {
        return rsdlFormatter.format(schema);
    }

    public List<RelationshipDefinition> parseRelationshipsFromRsdl(String rsdlText) {
        return rsdlFormatter.parseRelationships(rsdlText);
    }

    public String formatToTmdl(SemanticModelSchema schema) {
        return rsdlFormatter.formatToTmdl(schema);
    }

    public SemanticModelSchema applyTmdlScript(UUID modelId, String tmdlScript) {
        SemanticModelSchema nextSchema = rsdlFormatter.applyTmdlToSchema(getModel(modelId), tmdlScript);
        registerModel(nextSchema);
        return nextSchema;
    }

    private boolean detectSnowflakeViolations(
            List<RelationshipDefinition> relationships,
            Map<String, TableDefinition> tableMap,
            List<DiagnosticMarker> diagnostics) {
        boolean violatesStar = false;
        for (RelationshipDefinition rel : relationships) {
            TableDefinition from = tableMap.get(rel.fromTable().toLowerCase(Locale.ROOT));
            TableDefinition to = tableMap.get(rel.toTable().toLowerCase(Locale.ROOT));
            if (from != null && to != null
                    && "DIMENSION".equalsIgnoreCase(from.role())
                    && "DIMENSION".equalsIgnoreCase(to.role())) {
                violatesStar = true;
                diagnostics.add(DiagnosticMarker.error(1, 0, rel.name().length(), "ERR_SNOWFLAKE_REJECTED",
                        "Snowflake normalization between dimensions '" + from.name() + "' and '" + to.name()
                                + "' is rejected; flatten dimensions into a Star Schema."));
            }
        }
        return violatesStar;
    }

    private boolean detectAmbiguousPaths(
            List<RelationshipDefinition> relationships,
            Map<String, List<String>> adjacency,
            List<DiagnosticMarker> diagnostics) {
        Map<String, List<RelationshipDefinition>> activePairEdges = new HashMap<>();
        for (RelationshipDefinition rel : relationships) {
            String f = rel.fromTable().toLowerCase(Locale.ROOT);
            String t = rel.toTable().toLowerCase(Locale.ROOT);
            adjacency.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
            if (rel.isActive()) {
                String pairKey = f.compareTo(t) < 0 ? f + "::" + t : t + "::" + f;
                activePairEdges.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(rel);
            }
        }
        boolean hasAmbiguous = false;
        for (Map.Entry<String, List<RelationshipDefinition>> entry : activePairEdges.entrySet()) {
            if (entry.getValue().size() > 1) {
                hasAmbiguous = true;
                String relNames = entry.getValue().stream().map(RelationshipDefinition::name).toList().toString();
                diagnostics.add(DiagnosticMarker.error(1, 0, entry.getKey().length(), "ERR_AMBIGUOUS_PATH",
                        "Multiple active relationships detected between entities (" + entry.getKey() + "): "
                                + relNames + ". Persist secondary relationships as isActive: false and use USERELATIONSHIP()."));
            }
        }
        return hasAmbiguous;
    }

    private boolean detectDirectedCycles(Map<String, List<String>> adjacency, List<DiagnosticMarker> diagnostics) {
        Map<String, Integer> visitState = new HashMap<>();
        for (String node : adjacency.keySet()) {
            if (visitState.getOrDefault(node, 0) == 0 && detectCycleDfs(node, adjacency, visitState)) {
                diagnostics.add(DiagnosticMarker.error(1, 0, node.length(), "ERR_CIRCULAR_DEP",
                        "Circular relationship dependency cycle detected involving entity '" + node + "'."));
                return true;
            }
        }
        return false;
    }

    private boolean detectCycleDfs(String current, Map<String, List<String>> adjacency, Map<String, Integer> visitState) {
        visitState.put(current, 1);
        for (String neighbor : adjacency.getOrDefault(current, List.of())) {
            int state = visitState.getOrDefault(neighbor, 0);
            if (state == 1 || (state == 0 && detectCycleDfs(neighbor, adjacency, visitState))) {
                return true;
            }
        }
        visitState.put(current, 2);
        return false;
    }

    private SemanticModelSchema requireRegisteredModel(UUID modelId) {
        Objects.requireNonNull(modelId, "modelId must not be null");
        SemanticModelSchema found = registeredModels.get(modelId);
        if (found == null) {
            throw new IllegalArgumentException("Model not found for id: " + modelId);
        }
        return found;
    }

    private RelationshipDefinition validateAndNormalizeRelationship(
            SemanticModelSchema schema,
            RelationshipDefinition rel) {
        Objects.requireNonNull(rel, "relationship must not be null");
        requireNonBlank(rel.name(), "relationship.name");
        requireNonBlank(rel.fromTable(), "relationship.fromTable");
        requireNonBlank(rel.fromColumn(), "relationship.fromColumn");
        requireNonBlank(rel.toTable(), "relationship.toTable");
        requireNonBlank(rel.toColumn(), "relationship.toColumn");
        if (rel.fromTable().trim().equalsIgnoreCase(rel.toTable().trim())) {
            throw new IllegalArgumentException("Self-referential relationships are not supported: " + rel.fromTable());
        }
        TableDefinition fromTable = findTableOrThrow(schema, rel.fromTable());
        ColumnDefinition fromCol = findColumnOrThrow(fromTable, rel.fromColumn());
        TableDefinition toTable = findTableOrThrow(schema, rel.toTable());
        ColumnDefinition toCol = findColumnOrThrow(toTable, rel.toColumn());
        return new RelationshipDefinition(
                rel.name().trim(), fromTable.name(), fromCol.name(), toTable.name(), toCol.name(),
                rel.cardinality() != null ? rel.cardinality() : Cardinality.MANY_TO_ONE,
                rel.crossFilterDirection() != null ? rel.crossFilterDirection() : CrossFilterDirection.SINGLE,
                rel.isActive(), rel.bridgeTable());
    }

    private String resolveValidatedDateColumn(TableDefinition targetTable, DateTableSpec spec) {
        if (!spec.dateTable()) {
            return "";
        }
        String candidate = !spec.dateColumn().isBlank()
                ? spec.dateColumn()
                : relationshipInferenceService.inferDateColumnName(targetTable);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("No valid date column found in table: " + targetTable.name());
        }
        return findColumnOrThrow(targetTable, candidate).name();
    }

    private ModelMutationResult replaceRelationship(
            SemanticModelSchema schema,
            String targetName,
            RelationshipDefinition replacement) {
        List<RelationshipDefinition> updated = schema.relationships().stream()
                .map(r -> r.name().equalsIgnoreCase(targetName) ? replacement : r)
                .toList();
        return finalizeMutation(withTablesAndRelationships(schema, schema.tables(), updated));
    }

    private ModelMutationResult finalizeMutation(SemanticModelSchema updatedSchema) {
        DagValidationReport report = validateDagConsistency(updatedSchema);
        String rsdl = rsdlFormatter.format(updatedSchema);
        registerModel(updatedSchema);
        return new ModelMutationResult(updatedSchema, report, rsdl);
    }

    private static RelationshipDefinition findRelationshipOrThrow(SemanticModelSchema schema, String relationshipName) {
        Objects.requireNonNull(schema, "schema must not be null");
        requireNonBlank(relationshipName, "relationshipName");
        return schema.relationships().stream()
                .filter(r -> r.name().equalsIgnoreCase(relationshipName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Relationship not found: " + relationshipName));
    }

    private static TableDefinition findTableOrThrow(SemanticModelSchema schema, String tableName) {
        requireNonBlank(tableName, "tableName");
        return schema.tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Table not found: " + tableName));
    }

    private static ColumnDefinition findColumnOrThrow(TableDefinition table, String columnName) {
        requireNonBlank(columnName, "columnName");
        return table.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(columnName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Column '" + columnName + "' not found in table '" + table.name() + "'"));
    }

    private static List<TableDefinition> markColumnAsForeignKey(List<TableDefinition> tables, ColumnTarget target) {
        return tables.stream()
                .map(t -> !t.name().equalsIgnoreCase(target.tableName())
                        ? t
                        : copyTableWithColumns(t, t.columns().stream()
                                .map(c -> c.name().equalsIgnoreCase(target.columnName())
                                        ? copyColumnWithFlags(c, new ColumnFlags(c.isPrimaryKey(), true, c.isHidden()))
                                        : c)
                                .toList()))
                .toList();
    }

    private static SemanticModelSchema withTablesAndRelationships(
            SemanticModelSchema base,
            List<TableDefinition> tables,
            List<RelationshipDefinition> relationships) {
        return new SemanticModelSchema(
                base.modelId(), base.name(), base.fiscalYearStartMonth(),
                tables, relationships, base.measures(), base.rlsRolePredicates(),
                base.discourageImplicitMeasures());
    }

    private static TableDefinition copyTableWithColumns(TableDefinition t, List<ColumnDefinition> columns) {
        return new TableDefinition(
                t.name(), t.role(), t.partitionColumn(), t.estimatedRows(), t.scdType2(),
                t.description(), t.isHidden(), t.isDateTable(), t.dateColumn(), columns);
    }

    private static TableDefinition copyTableWithDateSettings(TableDefinition t, boolean isDateTable, String dateColumn) {
        return new TableDefinition(
                t.name(), t.role(), t.partitionColumn(), t.estimatedRows(), t.scdType2(),
                t.description(), t.isHidden(), isDateTable, dateColumn, t.columns());
    }

    private static String validateSortByColumn(
            TableDefinition targetTable,
            String sourceColumnName,
            String sortByColumn) {
        if (sortByColumn == null) {
            return null;
        }
        if (sortByColumn.isEmpty()) {
            return "";
        }
        if (sortByColumn.equalsIgnoreCase(sourceColumnName)) {
            throw new IllegalArgumentException("Column '" + sourceColumnName + "' cannot sort by itself.");
        }
        return findColumnOrThrow(targetTable, sortByColumn).name();
    }

    private static List<TableDefinition> replaceColumnInTableList(
            List<TableDefinition> tables,
            String tableName,
            ColumnDefinition updatedCol) {
        return tables.stream()
                .map(t -> !t.name().equalsIgnoreCase(tableName)
                        ? t
                        : copyTableWithColumns(t, t.columns().stream()
                                .map(c -> c.name().equalsIgnoreCase(updatedCol.name()) ? updatedCol : c)
                                .toList()))
                .toList();
    }

    private static TableDefinition applyDateTableColumnSettings(
            TableDefinition table,
            boolean isDateTable,
            String resolvedDateCol) {
        if (!isDateTable) {
            return copyTableWithDateSettings(table, false, "");
        }
        List<ColumnDefinition> updatedColumns = table.columns().stream()
                .map(c -> formatDateTableColumn(c, resolvedDateCol))
                .toList();
        return new TableDefinition(
                table.name(), "DIMENSION", table.partitionColumn(), table.estimatedRows(), table.scdType2(),
                table.description(), table.isHidden(), true, resolvedDateCol, updatedColumns);
    }

    private static ColumnDefinition formatDateTableColumn(ColumnDefinition c, String resolvedDateCol) {
        if (c.name().equalsIgnoreCase(resolvedDateCol)) {
            String nextType = c.dataType() != null && c.dataType().toUpperCase(Locale.ROOT).contains("DATE")
                    ? c.dataType()
                    : "DATE";
            return new ColumnDefinition(
                    c.name(), nextType, true, c.isForeignKey(), c.isHidden(),
                    c.piiClassification(), c.description(), c.displayFolder(),
                    SummarizationMode.NONE.label(), c.expression(), c.sortByColumn(), c.format());
        }
        String lower = c.name().toLowerCase(Locale.ROOT);
        if (lower.contains("year") || lower.contains("month") || lower.contains("day") || lower.contains("quarter")) {
            return new ColumnDefinition(
                    c.name(), c.dataType(), c.isPrimaryKey(), c.isForeignKey(), c.isHidden(),
                    c.piiClassification(), c.description(), c.displayFolder(),
                    SummarizationMode.NONE.label(), c.expression(), c.sortByColumn(), c.format());
        }
        return c;
    }

    private static ColumnDefinition copyColumnWithFlags(ColumnDefinition c, ColumnFlags flags) {
        return new ColumnDefinition(
                c.name(), c.dataType(), flags.primaryKey(), flags.foreignKey(), flags.hidden(),
                c.piiClassification(), c.description(), c.displayFolder(),
                c.summarizeBy(), c.expression(), c.sortByColumn(), c.format());
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank.");
        }
    }
}
