package com.rollercoaster.rax.compiler;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.MeasureDefinition;
import com.rollercoaster.model.RelationshipDefinition;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InfoFunctionEvaluator {

    private static final Pattern BRACKET_REF_PATTERN = Pattern.compile("(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_]*))?\\[([^\\]]+)\\]");

    public boolean isInfoQuery(String raxExpression) {
        if (raxExpression == null || raxExpression.isBlank()) {
            return false;
        }
        String upper = raxExpression.toUpperCase();
        return upper.contains("INFO.VIEW.TABLES")
                || upper.contains("INFO.VIEW.COLUMNS")
                || upper.contains("INFO.VIEW.MEASURES")
                || upper.contains("INFO.VIEW.RELATIONSHIPS")
                || upper.contains("INFO.PARTITIONS")
                || upper.contains("INFO.CALCDEPENDENCY")
                || upper.contains("INFO.STORAGETABLECOLUMNS");
    }

    public List<Map<String, Object>> evaluateInfoQuery(String raxExpression, SemanticModelSchema schema) {
        if (raxExpression == null || raxExpression.isBlank()) {
            return List.of();
        }
        String upper = raxExpression.toUpperCase();
        if (upper.contains("UNION(") && upper.contains("INFO.VIEW.COLUMNS") && upper.contains("INFO.VIEW.MEASURES")) {
            return buildUnifiedDataDictionary(schema);
        }
        if (upper.contains("INFO.CALCDEPENDENCY")) {
            return evaluateInfoCalcDependency(schema);
        }
        if (upper.contains("INFO.STORAGETABLECOLUMNS")) {
            return evaluateInfoStorageTableColumns(schema);
        }
        if (upper.contains("INFO.PARTITIONS")) {
            return evaluateInfoPartitions(schema);
        }
        if (upper.contains("INFO.VIEW.RELATIONSHIPS")) {
            return evaluateInfoViewRelationships(schema);
        }
        if (upper.contains("INFO.VIEW.MEASURES")) {
            return evaluateInfoViewMeasures(schema);
        }
        if (upper.contains("INFO.VIEW.COLUMNS")) {
            return evaluateInfoViewColumns(schema);
        }
        if (upper.contains("INFO.VIEW.TABLES")) {
            return evaluateInfoViewTables(schema);
        }
        return List.of();
    }

    public List<Map<String, Object>> evaluateInfoViewTables(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.tables() == null) {
            return rows;
        }
        int index = 1;
        for (TableDefinition table : schema.tables()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", index++);
            row.put("Name", table.name());
            row.put("Role", table.role() != null ? table.role() : "DIMENSION");
            row.put("ColumnsCount", table.columns() != null ? table.columns().size() : 0);
            row.put("Description", table.description() != null ? table.description() : "");
            row.put("StorageMode", table.role() != null && table.role().equalsIgnoreCase("FACT") ? "DuckDB Columnar / MySQL Partitioned" : "Import");
            row.put("Expression", "");
            row.put("IsHidden", false);
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoViewColumns(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.tables() == null) {
            return rows;
        }
        for (TableDefinition table : schema.tables()) {
            if (table.columns() == null) {
                continue;
            }
            for (ColumnDefinition column : table.columns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Table", table.name());
                row.put("Name", column.name());
                row.put("DataType", column.dataType());
                row.put("KeyType", column.isPrimaryKey() ? "PK" : column.isForeignKey() ? "FK" : "None");
                row.put("Description", column.description() != null ? column.description() : "");
                row.put("IsHidden", column.isHidden());
                row.put("IsPrimaryKey", column.isPrimaryKey());
                row.put("Expression", "");
                rows.add(row);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoViewMeasures(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.measures() == null) {
            return rows;
        }
        int index = 1;
        for (MeasureDefinition measure : schema.measures()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", index++);
            row.put("Table", measure.tableName());
            row.put("Name", measure.name());
            row.put("Expression", measure.raxExpression());
            row.put("FormatString", measure.formatString() != null ? measure.formatString() : "");
            row.put("Description", measure.description() != null ? measure.description() : "");
            row.put("IsHidden", false);
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoPartitions(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.tables() == null) {
            return rows;
        }
        String refreshedTime = Instant.now().toString();
        for (TableDefinition table : schema.tables()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Table", table.name());
            row.put("Name", table.name() + "_p2026");
            row.put("PartitionName", table.name() + "_p2026");
            row.put("QueryDefinition", "SELECT * FROM " + table.name());
            row.put("Type", 1);
            row.put("Mode", "Import (Parquet)");
            row.put("Engine", "DUCKDB");
            row.put("Rows", table.estimatedRows() > 0 ? table.estimatedRows() : 1000L);
            row.put("RefreshedTime", refreshedTime);
            row.put("State", "READY");
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoStorageTableColumns(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.tables() == null) {
            return rows;
        }
        for (TableDefinition table : schema.tables()) {
            if (table.columns() == null) {
                continue;
            }
            long rowCount = Math.max(100L, table.estimatedRows());
            for (ColumnDefinition column : table.columns()) {
                long bytesPerValue = column.dataType() != null && column.dataType().toUpperCase().contains("VARCHAR") ? 32L : 8L;
                long dictionaryBytes = Math.max(1024L, rowCount * bytesPerValue);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("DIMENSION_NAME", table.name());
                row.put("ATTRIBUTE_NAME", column.name());
                row.put("DATA_TYPE", column.dataType());
                row.put("DICTIONARY_SIZE", dictionaryBytes);
                row.put("SizeKB", Math.round((dictionaryBytes / 1024.0) * 100.0) / 100.0);
                rows.add(row);
            }
        }
        rows.sort((a, b) -> Long.compare(
                ((Number) b.get("DICTIONARY_SIZE")).longValue(),
                ((Number) a.get("DICTIONARY_SIZE")).longValue()));
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoViewRelationships(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.relationships() == null) {
            return rows;
        }
        for (RelationshipDefinition rel : schema.relationships()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Name", rel.name());
            row.put("FromTable", rel.fromTable());
            row.put("FromColumn", rel.fromColumn());
            row.put("ToTable", rel.toTable());
            row.put("ToColumn", rel.toColumn());
            row.put("Cardinality", rel.cardinality() != null ? rel.cardinality().name() : "MANY_TO_ONE");
            row.put("CrossFilterDirection", rel.crossFilterDirection() != null ? rel.crossFilterDirection().name() : "SINGLE");
            row.put("IsActive", rel.isActive());
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> evaluateInfoCalcDependency(SemanticModelSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (schema == null || schema.measures() == null) {
            return rows;
        }
        Map<String, MeasureDefinition> measuresByName = new LinkedHashMap<>();
        for (MeasureDefinition measureDefinition : schema.measures()) {
            measuresByName.put(measureDefinition.name().toLowerCase(), measureDefinition);
        }

        for (MeasureDefinition measure : schema.measures()) {
            String raxExpression = measure.raxExpression() != null ? measure.raxExpression() : "";
            Matcher matcher = BRACKET_REF_PATTERN.matcher(raxExpression);
            while (matcher.find()) {
                String quotedTable = matcher.group(1);
                String unquotedTable = matcher.group(2);
                String refTable = quotedTable != null ? quotedTable : unquotedTable;
                String refObject = matcher.group(3);

                Map<String, Object> dependencyRow = new LinkedHashMap<>();
                dependencyRow.put("OBJECT_TYPE", "MEASURE");
                dependencyRow.put("TABLE", measure.tableName());
                dependencyRow.put("OBJECT", measure.name());
                dependencyRow.put("EXPRESSION", raxExpression);

                if (refTable == null && measuresByName.containsKey(refObject.toLowerCase())) {
                    MeasureDefinition target = measuresByName.get(refObject.toLowerCase());
                    dependencyRow.put("REFERENCED_OBJECT_TYPE", "MEASURE");
                    dependencyRow.put("REFERENCED_TABLE", target.tableName());
                    dependencyRow.put("REFERENCED_OBJECT", target.name());
                    dependencyRow.put("REFERENCED_EXPRESSION", target.raxExpression());
                } else {
                    dependencyRow.put("REFERENCED_OBJECT_TYPE", "COLUMN");
                    dependencyRow.put("REFERENCED_TABLE", refTable != null ? refTable : measure.tableName());
                    dependencyRow.put("REFERENCED_OBJECT", refObject);
                    dependencyRow.put("REFERENCED_EXPRESSION", "");
                }
                rows.add(dependencyRow);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> buildUnifiedDataDictionary(SemanticModelSchema schema) {
        List<Map<String, Object>> dictionary = new ArrayList<>();
        for (Map<String, Object> columnRow : evaluateInfoViewColumns(schema)) {
            if (!Boolean.TRUE.equals(columnRow.get("IsHidden"))) {
                dictionary.add(Map.of(
                        "Type", "Column",
                        "Name", columnRow.get("Name"),
                        "Table", columnRow.get("Table"),
                        "Description", columnRow.get("Description"),
                        "Rax", columnRow.get("Expression")
                ));
            }
        }
        for (Map<String, Object> measureRow : evaluateInfoViewMeasures(schema)) {
            dictionary.add(Map.of(
                    "Type", "Measure",
                    "Name", measureRow.get("Name"),
                    "Table", measureRow.get("Table"),
                    "Description", measureRow.get("Description"),
                    "Rax", measureRow.get("Expression")
            ));
        }
        for (Map<String, Object> tableRow : evaluateInfoViewTables(schema)) {
            dictionary.add(Map.of(
                    "Type", "Table",
                    "Name", tableRow.get("Name"),
                    "Table", "n/a",
                    "Description", tableRow.get("Description"),
                    "Rax", tableRow.get("Expression")
            ));
        }
        return dictionary;
    }
}
