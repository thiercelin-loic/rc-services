package com.rollercoaster.writeback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "writeback_staging")
public class WritebackStagingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "target_table", nullable = false, length = 128)
    private String targetTable;

    @Column(name = "record_id_json", nullable = false, columnDefinition = "TEXT")
    private String recordIdJson;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WritebackStagingEntity() {
    }

    public WritebackStagingEntity(
            UUID id,
            String tenantId,
            String userId,
            String targetTable,
            String recordIdJson,
            String payloadJson,
            long expectedVersion,
            String status,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.targetTable = targetTable;
        this.recordIdJson = recordIdJson;
        this.payloadJson = payloadJson;
        this.expectedVersion = expectedVersion;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getRecordIdJson() {
        return recordIdJson;
    }

    public void setRecordIdJson(String recordIdJson) {
        this.recordIdJson = recordIdJson;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
