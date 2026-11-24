package com.rollercoaster.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats {@link SemanticModelSchema} instances into canonical RollerCoaster Semantic Definition Language (RSDL)
 * and Tabular Model Definition Language (TMDL), and parses RSDL relationship and TMDL measure scripts.
 */
@Component
public class RsdlFormatter {

    private static final Pattern RSDL_REL_PATTERN = Pattern.compile(
            "relationship\\s+'([^']+)'\\s+fromColumn:\\s+'([^']+)'\\[([^\\]]+)\\]\\s+toColumn:\\s+'([^']+)'\\[([^\\]]+)\\]"
                    + "(?:\\s+cardinality:\\s*(\\S+))?(?:\\s+crossFilter:\\s*(\\S+))?\\s+isActive:\\s*(true|false)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TMDL_TABLE_LINE = Pattern.compile(
            "(?i)^\\s*table\\s+(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_\\s-]*))\\s*$");
    private static final Pattern TMDL_MEASURE_LINE = Pattern.compile(
            "(?i)^\\s*measure\\s+(?:'([^']+)'|([\\p{L}_][\\p{L}\\p{Nd}_\\s-]*))\\s*=\\s*(.+)$");
    private static final Pattern TMDL_FORMAT_LINE = Pattern.compile(
            "(?i)^\\s*formatString:\\s*\"([^\"]*)\"\\s*$");

    /**
     * Generates canonical RSDL text from a {@link SemanticModelSchema}.
     */
    public String format(SemanticModelSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        StringBuilder sb = new StringBuilder();
        String modelName = (schema.name() != null && !schema.name().isBlank()) ? schema.name() : "Untitled Model";

        sb.append("// RollerCoaster Semantic Definition Language (RSDL v1.0)\n");
        sb.append("model '").append(modelName).append("' {\n");
        sb.append("  fiscalYearStartMonth: ").append(schema.fiscalYearStartMonth()).append("\n");
        if (schema.discourageImplicitMeasures()) {
            sb.append("  discourageImplicitMeasures: true\n");
        }
        sb.append("\n");

        appendTables(sb, schema.tables());
        appendRelationships(sb, schema.relationships());
        appendMeasures(sb, schema.measures());
        appendRlsPredicates(sb, schema.rlsRolePredicates());

        sb.append("}\n");
        return sb.toString();
    }

    public String formatToRsdl(SemanticModelSchema schema) {
        return format(schema);
    }

    public String formatRelationship(RelationshipDefinition rel) {
        Objects.requireNonNull(rel, "rel must not be null");
        Cardinality effectiveCardinality = rel.cardinality() != null ? rel.cardinality() : Cardinality.MANY_TO_ONE;
        CrossFilterDirection effectiveDirection = rel.crossFilterDirection() != null
                ? rel.crossFilterDirection()
                : CrossFilterDirection.SINGLE;

        String cardinalityLabel = switch (effectiveCardinality) {
            case ONE_TO_ONE -> "OneToOne";
            case MANY_TO_ONE -> "ManyToOne";
            case ONE_TO_MANY -> "OneToMany";
            case MANY_TO_MANY -> "ManyToMany";
        };
        String crossFilterLabel = switch (effectiveDirection) {
            case SINGLE -> "Single";
            case BOTH -> "Both";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("  relationship '").append(rel.name()).append("'\n");
        sb.append("    fromColumn: '").append(rel.fromTable()).append("'[").append(rel.fromColumn()).append("]\n");
        sb.append("    toColumn: '").append(rel.toTable()).append("'[").append(rel.toColumn()).append("]\n");
        sb.append("    cardinality: ").append(cardinalityLabel).append("\n");
        sb.append("    crossFilter: ").append(crossFilterLabel).append("\n");
        sb.append("    isActive: ").append(rel.isActive()).append("\n");
        if (rel.bridgeTable() != null && !rel.bridgeTable().isBlank()) {
            sb.append("    bridgeTable: '").append(rel.bridgeTable()).append("'\n");
        }
        return sb.toString();
    }

    public List<RelationshipDefinition> parseRelationships(String rsdlText) {
        if (rsdlText == null || rsdlText.isBlank()) {
            return List.of();
        }
        List<RelationshipDefinition> parsed = new ArrayList<>();
        Matcher matcher = RSDL_REL_PATTERN.matcher(rsdlText);
        while (matcher.find()) {
            String relName = matcher.group(1);
            String fromTable = matcher.group(2);
            String fromCol = matcher.group(3);
            String toTable = matcher.group(4);
            String toCol = matcher.group(5);
            Cardinality cardinality = matcher.group(6) != null
                    ? Cardinality.fromToken(matcher.group(6))
                    : Cardinality.MANY_TO_ONE;
            CrossFilterDirection crossFilter = matcher.group(7) != null
                    ? CrossFilterDirection.fromToken(matcher.group(7))
                    : CrossFilterDirection.SINGLE;
            boolean isActive = Boolean.parseBoolean(matcher.group(8));
            parsed.add(new RelationshipDefinition(
                    relName, fromTable, fromCol, toTable, toCol, cardinality, crossFilter, isActive));
        }
        return List.copyOf(parsed);
    }

    public String formatToTmdl(SemanticModelSchema schema) {
        if (schema == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("createOrReplace\n");
        Map<String, List<MeasureDefinition>> byTable = new LinkedHashMap<>();
        String fallbackTable = !schema.tables().isEmpty() ? schema.tables().get(0).name() : "Measures Table";
        for (MeasureDefinition m : schema.measures()) {
            String tName = (m.tableName() != null && !m.tableName().isBlank()) ? m.tableName() : fallbackTable;
            byTable.computeIfAbsent(tName, k -> new ArrayList<>()).add(m);
        }
        if (byTable.isEmpty()) {
            return sb.append("  table '").append(fallbackTable).append("'\n").toString();
        }
        for (Map.Entry<String, List<MeasureDefinition>> entry : byTable.entrySet()) {
            sb.append("  table '").append(entry.getKey()).append("'\n");
            for (MeasureDefinition m : entry.getValue()) {
                if (!m.description().isBlank()) {
                    sb.append("    /// ").append(m.description()).append("\n");
                }
                sb.append("    measure '").append(m.name()).append("' = ").append(m.raxExpression()).append("\n");
                if (m.formatString() != null && !m.formatString().isBlank()) {
                    sb.append("      formatString: \"").append(m.formatString()).append("\"\n");
                }
            }
        }
        return sb.toString();
    }

    public SemanticModelSchema applyTmdlToSchema(SemanticModelSchema current, String tmdlScript) {
        Objects.requireNonNull(current, "current must not be null");
        if (tmdlScript == null || tmdlScript.isBlank()) {
            return current;
        }
        Map<String, MeasureDefinition> updatedMeasures = new LinkedHashMap<>();
        for (MeasureDefinition existing : current.measures()) {
            updatedMeasures.put(existing.name().toLowerCase(Locale.ROOT), existing);
        }
        String initialTable = !current.tables().isEmpty() ? current.tables().get(0).name() : "Measures Table";
        parseTmdlLines(tmdlScript.split("\\r?\\n"), initialTable, updatedMeasures);
        return new SemanticModelSchema(
                current.modelId(), current.name(), current.fiscalYearStartMonth(),
                current.tables(), current.relationships(), new ArrayList<>(updatedMeasures.values()),
                current.rlsRolePredicates(), current.discourageImplicitMeasures());
    }

    private void parseTmdlLines(String[] lines, String initialTable, Map<String, MeasureDefinition> updatedMeasures) {
        String currentTable = initialTable;
        StringBuilder pendingDesc = new StringBuilder();
        String lastKey = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("///")) {
                String text = trimmed.substring(3).trim();
                if (!text.isEmpty()) {
                    if (pendingDesc.length() > 0) {
                        pendingDesc.append(" ");
                    }
                    pendingDesc.append(text);
                }
                continue;
            }
            Matcher tableMatcher = TMDL_TABLE_LINE.matcher(line);
            if (tableMatcher.matches()) {
                String tName = tableMatcher.group(1) != null ? tableMatcher.group(1) : tableMatcher.group(2);
                currentTable = (tName != null && !tName.isBlank()) ? tName.trim() : currentTable;
                pendingDesc.setLength(0);
                continue;
            }
            Matcher measureMatcher = TMDL_MEASURE_LINE.matcher(line);
            if (measureMatcher.matches()) {
                lastKey = upsertTmdlMeasure(measureMatcher, currentTable + "::" + pendingDesc, updatedMeasures);
                pendingDesc.setLength(0);
                continue;
            }
            Matcher formatMatcher = TMDL_FORMAT_LINE.matcher(line);
            if (formatMatcher.matches() && lastKey != null) {
                MeasureDefinition prev = updatedMeasures.get(lastKey);
                if (prev != null) {
                    updatedMeasures.put(lastKey, new MeasureDefinition(
                            prev.id(), prev.name(), prev.tableName(),
                            prev.raxExpression(), formatMatcher.group(1), prev.description()));
                }
            }
        }
    }

    private static String upsertTmdlMeasure(
            Matcher matcher,
            String tableAndDesc,
            Map<String, MeasureDefinition> updatedMeasures) {
        String rawName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        String expr = matcher.group(3) != null ? matcher.group(3).trim() : "";
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        int sepIdx = tableAndDesc.indexOf("::");
        String currentTable = sepIdx >= 0 ? tableAndDesc.substring(0, sepIdx) : tableAndDesc;
        String pendingDesc = sepIdx >= 0 ? tableAndDesc.substring(sepIdx + 2) : "";
        String cleanName = rawName.trim();
        String key = cleanName.toLowerCase(Locale.ROOT);
        MeasureDefinition prev = updatedMeasures.get(key);
        String id = prev != null ? prev.id() : "m_" + key.replaceAll("[^a-z0-9]+", "_");
        String desc = !pendingDesc.isEmpty() ? pendingDesc : (prev != null ? prev.description() : "");
        String fmt = (prev != null && prev.formatString() != null) ? prev.formatString() : "";
        updatedMeasures.put(key, new MeasureDefinition(id, cleanName, currentTable, expr, fmt, desc));
        return key;
    }

    private void appendTables(StringBuilder sb, List<TableDefinition> tables) {
        for (TableDefinition t : tables) {
            sb.append("  table '").append(t.name()).append("' (\n");
            sb.append("    role: ").append(t.role()).append("\n");
            if (t.isDateTable()) {
                sb.append("    isDateTable: true\n");
                if (!t.dateColumn().isBlank()) {
                    sb.append("    dateColumn: '").append(t.dateColumn()).append("'\n");
                }
            }
            if (t.isHidden()) {
                sb.append("    isHidden: true\n");
            }
            for (ColumnDefinition c : t.columns()) {
                sb.append("    column '").append(c.name()).append("': ").append(c.dataType());
                if (c.isPrimaryKey()) {
                    sb.append(" [PK]");
                }
                if (c.isForeignKey()) {
                    sb.append(" [FK]");
                }
                if (c.isHidden()) {
                    sb.append(" [Hidden]");
                }
                sb.append("\n");
            }
            sb.append("  )\n\n");
        }
    }

    private void appendRelationships(StringBuilder sb, List<RelationshipDefinition> relationships) {
        for (RelationshipDefinition rel : relationships) {
            sb.append(formatRelationship(rel)).append("\n");
        }
    }

    private void appendMeasures(StringBuilder sb, List<MeasureDefinition> measures) {
        for (MeasureDefinition m : measures) {
            if (m.description() != null && !m.description().isBlank()) {
                sb.append("  /// ").append(m.description()).append("\n");
            }
            sb.append("  measure '").append(m.tableName()).append("'[").append(m.name()).append("] = ")
                    .append(m.raxExpression()).append("\n");
            if (m.formatString() != null && !m.formatString().isBlank()) {
                sb.append("    formatString: \"").append(m.formatString()).append("\"\n");
            }
        }
    }

    private void appendRlsPredicates(StringBuilder sb, Map<String, String> rlsRolePredicates) {
        if (rlsRolePredicates == null || rlsRolePredicates.isEmpty()) {
            return;
        }
        sb.append("\n");
        rlsRolePredicates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append("  role '").append(entry.getKey()).append("' {\n");
                    sb.append("    filterPredicate: ").append(entry.getValue()).append("\n");
                    sb.append("  }\n");
                });
    }
}
