package com.rollercoaster.model;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages the in-process embedded DuckDB analytical engine via JDBC/JNI bindings.
 * Enforces strict cgroup memory governance and air-gapped isolation at startup:
 * - SET max_memory = '4GB';
 * - SET threads = 8;
 * - SET autoinstall_known_extensions = false;
 * - SET autoload_known_extensions = false;
 */
@Component
public class DuckDbConnectionManager {

    private final String maxMemory;
    private final int threads;
    private final ReadWriteLock partitionLock = new ReentrantReadWriteLock();
    private final Map<String, Path> tenantBoundPartitions = new ConcurrentHashMap<>();
    private Connection primaryConnection;

    public DuckDbConnectionManager(
            @Value("${rollercoaster.duckdb.max-memory:4GB}") String maxMemory,
            @Value("${rollercoaster.duckdb.threads:8}") int threads) {
        this.maxMemory = maxMemory;
        this.threads = threads;
    }

    @PostConstruct
    public synchronized void initializeEngine() {
        if (this.primaryConnection != null) {
            return;
        }
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            this.primaryConnection = DriverManager.getConnection("jdbc:duckdb:");
            applyGovernancePragmas(this.primaryConnection);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize embedded DuckDB JNI engine", e);
        }
    }

    @PreDestroy
    public synchronized void shutdownEngine() {
        if (this.primaryConnection != null) {
            try {
                this.primaryConnection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public Connection acquireConnection() throws SQLException {
        if (this.primaryConnection == null || this.primaryConnection.isClosed()) {
            initializeEngine();
        }
        // Create a lightweight duplicate connection sharing the same in-process DuckDB database instance
        try {
            if (this.primaryConnection.isWrapperFor(org.duckdb.DuckDBConnection.class)) {
                Connection dup = this.primaryConnection.unwrap(org.duckdb.DuckDBConnection.class).duplicate();
                applyGovernancePragmas(dup);
                return dup;
            }
        } catch (Exception ignored) {
        }
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    /**
     * Enforces resource limits and air-gapped extension isolation on every DuckDB connection.
     */
    public void applyGovernancePragmas(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET max_memory = '" + maxMemory + "';");
            stmt.execute("SET threads = " + threads + ";");
            stmt.execute("SET autoinstall_known_extensions = false;");
            stmt.execute("SET autoload_known_extensions = false;");
        }
    }

    /**
     * Atomically rebinds active columnar Parquet partition views across worker threads following
     * Incremental Refresh synchronization.
     */
    public void rebindParquetPartitions(String tenantId, Path parquetFile) {
        partitionLock.writeLock().lock();
        try {
            tenantBoundPartitions.put(tenantId, parquetFile);
            String viewName = "v_fact_partition_" + tenantId.replaceAll("[^a-zA-Z0-9_]", "_");
            String normalizedPath = parquetFile.toAbsolutePath().toString().replace('\\', '/');
            try (Connection conn = acquireConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE VIEW " + viewName
                        + " AS SELECT * FROM read_parquet('" + normalizedPath + "')");
            } catch (Exception ignored) {
            }
        } finally {
            partitionLock.writeLock().unlock();
        }
    }

    public Path getBoundPartition(String tenantId) {
        return tenantBoundPartitions.get(tenantId);
    }

    public List<Map<String, Object>> executeColumnarQuery(String sql) throws SQLException {
        partitionLock.readLock().lock();
        try (Connection conn = acquireConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } finally {
            partitionLock.readLock().unlock();
        }
    }

    /**
     * Ensures all tables and columns declared in the semantic model exist in the embedded DuckDB catalog.
     */
    public void synchronizeModelTables(SemanticModelSchema schema) {
        if (schema == null || schema.tables() == null || schema.tables().isEmpty()) {
            return;
        }
        partitionLock.writeLock().lock();
        try (Connection conn = acquireConnection();
             Statement stmt = conn.createStatement()) {
            for (TableDefinition table : schema.tables()) {
                String safeTable = sanitizeIdentifier(table.name());
                if (safeTable.isEmpty()) {
                    continue;
                }
                List<ColumnDefinition> columns = table.columns();
                if (columns == null || columns.isEmpty()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS " + safeTable + " (row_id BIGINT)");
                    continue;
                }
                List<String> colDefs = new ArrayList<>();
                for (ColumnDefinition col : columns) {
                    String safeCol = sanitizeIdentifier(col.name());
                    if (!safeCol.isEmpty()) {
                        colDefs.add(safeCol + " " + mapToDuckDbType(col.dataType()));
                    }
                }
                if (!colDefs.isEmpty()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS " + safeTable + " (" + String.join(", ", colDefs) + ")");
                    for (ColumnDefinition col : columns) {
                        String safeCol = sanitizeIdentifier(col.name());
                        if (!safeCol.isEmpty()) {
                            stmt.execute("ALTER TABLE " + safeTable + " ADD COLUMN IF NOT EXISTS "
                                    + safeCol + " " + mapToDuckDbType(col.dataType()));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            partitionLock.writeLock().unlock();
        }
    }

    /**
     * Materializes dataset rows into an embedded DuckDB table for live visual query execution.
     */
    public int ingestTableRows(String tableName, List<Map<String, Object>> rows) {
        String safeTable = sanitizeIdentifier(tableName);
        if (safeTable.isEmpty() || rows == null || rows.isEmpty()) {
            return 0;
        }
        Map<String, String> inferredColumns = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String safeCol = sanitizeIdentifier(entry.getKey());
                if (safeCol.isEmpty()) {
                    continue;
                }
                Object val = entry.getValue();
                if (!inferredColumns.containsKey(safeCol) && val != null) {
                    if (val instanceof Number) {
                        inferredColumns.put(safeCol, "DOUBLE");
                    } else if (val instanceof Boolean) {
                        inferredColumns.put(safeCol, "BOOLEAN");
                    } else {
                        inferredColumns.put(safeCol, "VARCHAR");
                    }
                } else if (!inferredColumns.containsKey(safeCol)) {
                    inferredColumns.put(safeCol, "VARCHAR");
                }
            }
        }
        if (inferredColumns.isEmpty()) {
            return 0;
        }

        partitionLock.writeLock().lock();
        try (Connection conn = acquireConnection();
             Statement stmt = conn.createStatement()) {
            List<String> colDefs = new ArrayList<>();
            for (Map.Entry<String, String> col : inferredColumns.entrySet()) {
                colDefs.add(col.getKey() + " " + col.getValue());
            }
            stmt.execute("CREATE TABLE IF NOT EXISTS " + safeTable + " (" + String.join(", ", colDefs) + ")");
            for (Map.Entry<String, String> col : inferredColumns.entrySet()) {
                stmt.execute("ALTER TABLE " + safeTable + " ADD COLUMN IF NOT EXISTS " + col.getKey() + " " + col.getValue());
            }
            stmt.execute("DELETE FROM " + safeTable);

            List<String> colNames = new ArrayList<>(inferredColumns.keySet());
            int inserted = 0;
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                List<String> valuesSql = new ArrayList<>();
                for (String colName : colNames) {
                    Object rawVal = findIgnoreCase(row, colName);
                    valuesSql.add(formatDuckDbLiteral(rawVal));
                }
                stmt.execute("INSERT INTO " + safeTable + " (" + String.join(", ", colNames)
                        + ") VALUES (" + String.join(", ", valuesSql) + ")");
                inserted++;
            }
            return inserted;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ingest rows into DuckDB table " + safeTable + ": " + e.getMessage(), e);
        } finally {
            partitionLock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> getTableRows(String tableName) {
        String safeTable = sanitizeIdentifier(tableName);
        if (safeTable.isEmpty()) {
            return List.of();
        }
        try {
            return executeColumnarQuery("SELECT * FROM " + safeTable + " LIMIT 10000");
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object findIgnoreCase(Map<String, Object> row, String targetCol) {
        if (row.containsKey(targetCol)) {
            return row.get(targetCol);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (sanitizeIdentifier(entry.getKey()).equalsIgnoreCase(targetCol)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String formatDuckDbLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return "NULL";
        }
        return "'" + str.replace("'", "''") + "'";
    }

    public static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    private String mapToDuckDbType(String dataType) {
        if (dataType == null) {
            return "VARCHAR";
        }
        String upper = dataType.trim().toUpperCase();
        if (upper.contains("INT") || upper.equals("LONG")) {
            return "BIGINT";
        }
        if (upper.contains("FLOAT") || upper.contains("DOUBLE") || upper.contains("DEC")
                || upper.contains("NUM") || upper.contains("CURR")) {
            return "DOUBLE";
        }
        if (upper.contains("BOOL")) {
            return "BOOLEAN";
        }
        return "VARCHAR";
    }

}
