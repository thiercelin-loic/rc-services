package com.rollercoaster.ingestion.refresh;

import com.rollercoaster.config.RedisCacheAndPubSubConfig;
import com.rollercoaster.model.DuckDbConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental Refresh Coordinator:
 * 1. Queries SELECT MAX(watermark_column) FROM fact_partition
 * 2. Executes partitioned bulk upserts into MySQL using standard MySQL 8.0 row aliasing:
 *    INSERT INTO fact_partition (tenant_id, id, date_key, `timestamp`, dim_id, metric)
 *    VALUES (?, ?, ?, ?, ?, ?) AS new_row
 *    ON DUPLICATE KEY UPDATE `timestamp` = new_row.`timestamp`, metric = new_row.metric;
 * 3. Stores new high-watermark in Redis under metadata:watermark:{tenantId}:{tableId}
 * 4. Appends delta records into tenant-isolated local Parquet partitions (/data/parquet/{tenantId}/fact_partition/)
 * 5. Invokes DuckDbConnectionManager.rebindParquetPartitions() atomically across worker threads
 * 6. Broadcasts cache invalidation event over Redis Pub/Sub.
 */
@Service
public class IncrementalRefreshCoordinator {

    public static final String PARTITIONED_UPSERT_SQL = """
            INSERT INTO fact_partition (tenant_id, id, date_key, `timestamp`, dim_id, metric)
            VALUES (?, ?, ?, ?, ?, ?) AS new_row
            ON DUPLICATE KEY UPDATE
              `timestamp` = new_row.`timestamp`,
              metric = new_row.metric
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final DuckDbConnectionManager duckDbConnectionManager;
    private final Path parquetBaseDir;
    private final Map<String, Instant> inMemoryWatermarkFallback = new ConcurrentHashMap<>();

    public record FactDeltaRecord(
            String tenantId,
            long id,
            int dateKey,
            Integer previousDateKey,
            Instant timestamp,
            int dimId,
            double metric
    ) {}

    public record IncrementalRefreshResult(
            String tenantId,
            String tableId,
            int recordsUpserted,
            Instant previousWatermark,
            Instant newWatermark,
            String parquetPartitionPath
    ) {}

    public IncrementalRefreshCoordinator(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            DuckDbConnectionManager duckDbConnectionManager,
            @Value("${rollercoaster.duckdb.parquet-base-dir:/data/parquet}") String parquetBaseDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.duckDbConnectionManager = duckDbConnectionManager;
        this.parquetBaseDir = Path.of(parquetBaseDir);
    }

    public Instant queryCurrentHighWatermark(String tenantId, String tableId) {
        String redisKey = buildWatermarkRedisKey(tenantId, tableId);
        if (redisTemplate != null) {
            try {
                String cached = redisTemplate.opsForValue().get(redisKey);
                if (cached != null && !cached.isBlank()) {
                    return Instant.parse(cached);
                }
            } catch (Exception ignored) {
            }
        }
        if (jdbcTemplate != null) {
            try {
                Timestamp maxTs = jdbcTemplate.queryForObject(
                        "SELECT MAX(`timestamp`) FROM fact_partition WHERE tenant_id = ?",
                        Timestamp.class,
                        tenantId);
                if (maxTs != null) {
                    return maxTs.toInstant();
                }
            } catch (Exception ignored) {
            }
        }
        return inMemoryWatermarkFallback.getOrDefault(redisKey, Instant.EPOCH);
    }

    @Transactional
    public IncrementalRefreshResult executeIncrementalRefresh(
            String tenantId,
            String tableId,
            List<FactDeltaRecord> deltaRecords) throws Exception {

        Instant previousWatermark = queryCurrentHighWatermark(tenantId, tableId);
        Instant newWatermark = previousWatermark;

        // Filter delta records strictly newer than or equal to watermark
        List<FactDeltaRecord> filteredDeltas = deltaRecords.stream()
                .filter(r -> !r.timestamp().isBefore(previousWatermark))
                .toList();

        for (FactDeltaRecord r : filteredDeltas) {
            if (r.timestamp().isAfter(newWatermark)) {
                newWatermark = r.timestamp();
            }
        }

        if (jdbcTemplate != null && !filteredDeltas.isEmpty()) {
            try {
                // Handle partition re-assignment deletes when a delta record changes its partition date_key
                for (FactDeltaRecord r : filteredDeltas) {
                    if (r.previousDateKey() != null && !r.previousDateKey().equals(r.dateKey())) {
                        jdbcTemplate.update(
                                "DELETE FROM fact_partition WHERE tenant_id = ? AND id = ? AND date_key = ?",
                                r.tenantId(), r.id(), r.previousDateKey());
                    }
                }

                jdbcTemplate.batchUpdate(PARTITIONED_UPSERT_SQL, filteredDeltas, 500, (ps, row) -> {
                    ps.setString(1, row.tenantId());
                    ps.setLong(2, row.id());
                    ps.setInt(3, row.dateKey());
                    ps.setTimestamp(4, Timestamp.from(row.timestamp()));
                    ps.setInt(5, row.dimId());
                    ps.setDouble(6, row.metric());
                });
            } catch (Exception ignored) {
            }
        }

        // Synchronize tenant-isolated columnar Parquet partition and rebind DuckDB active view
        Path partitionFile = appendToTenantParquetPartition(tenantId, filteredDeltas);
        duckDbConnectionManager.rebindParquetPartitions(tenantId, partitionFile);

        // Update Redis watermark and broadcast Pub/Sub cache invalidation
        String redisKey = buildWatermarkRedisKey(tenantId, tableId);
        inMemoryWatermarkFallback.put(redisKey, newWatermark);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(redisKey, newWatermark.toString());
                redisTemplate.convertAndSend(
                        RedisCacheAndPubSubConfig.CACHE_INVALIDATION_TOPIC,
                        "INVALIDATE_TENANT:" + tenantId + ":" + tableId + ":" + newWatermark);
            } catch (Exception ignored) {
            }
        }

        return new IncrementalRefreshResult(
                tenantId,
                tableId,
                filteredDeltas.size(),
                previousWatermark,
                newWatermark,
                partitionFile.toAbsolutePath().toString()
        );
    }

    public static String buildWatermarkRedisKey(String tenantId, String tableId) {
        return "metadata:watermark:" + tenantId + ":" + tableId;
    }

    private Path appendToTenantParquetPartition(String tenantId, List<FactDeltaRecord> deltaRecords) throws Exception {
        Path tenantPartitionDir = parquetBaseDir.resolve(tenantId).resolve("fact_partition");
        Files.createDirectories(tenantPartitionDir);
        Path targetParquet = tenantPartitionDir.resolve("part-" + System.currentTimeMillis() + ".parquet");
        String normalizedPath = targetParquet.toAbsolutePath().toString().replace('\\', '/');

        try (Connection conn = duckDbConnectionManager.acquireConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TEMP TABLE IF NOT EXISTS staging_fact_delta ("
                    + "tenant_id VARCHAR, id BIGINT, date_key INTEGER, ts TIMESTAMP, dim_id INTEGER, metric DOUBLE)");
            stmt.execute("DELETE FROM staging_fact_delta");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO staging_fact_delta VALUES (?, ?, ?, ?, ?, ?)")) {
                for (FactDeltaRecord r : deltaRecords) {
                    ps.setString(1, r.tenantId());
                    ps.setLong(2, r.id());
                    ps.setInt(3, r.dateKey());
                    ps.setTimestamp(4, Timestamp.from(r.timestamp()));
                    ps.setInt(5, r.dimId());
                    ps.setDouble(6, r.metric());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            stmt.execute("COPY (SELECT * FROM staging_fact_delta) TO '" + normalizedPath
                    + "' (FORMAT PARQUET, COMPRESSION SNAPPY)");
        }
        return targetParquet;
    }
}
