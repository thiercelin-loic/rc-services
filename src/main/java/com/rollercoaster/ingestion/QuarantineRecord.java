package com.rollercoaster.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to quarantine_records for isolating rows that fail datatype constraints
 * or R-Query custom column evaluation ("quarantine" error policy) without halting ingestion streams.
 */
@Entity
@Table(name = "quarantine_records")
public class QuarantineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "dataset_id", nullable = false, length = 128)
    private String datasetId;

    @Column(name = "source_line_number", nullable = false)
    private long sourceLineNumber;

    @Column(name = "column_name", length = 128)
    private String columnName;

    @Column(name = "expected_type", length = 32)
    private String expectedType;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "error_reason", nullable = false, length = 512)
    private String errorReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public QuarantineRecord() {
    }

    public QuarantineRecord(
            String tenantId,
            String datasetId,
            long sourceLineNumber,
            String columnName,
            String expectedType,
            String rawPayload,
            String errorReason) {
        this.id = null;
        this.tenantId = tenantId;
        this.datasetId = datasetId;
        this.sourceLineNumber = sourceLineNumber;
        this.columnName = columnName;
        this.expectedType = expectedType;
        this.rawPayload = rawPayload;
        this.errorReason = errorReason;
        this.createdAt = Instant.now();
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

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public long getSourceLineNumber() {
        return sourceLineNumber;
    }

    public void setSourceLineNumber(long sourceLineNumber) {
        this.sourceLineNumber = sourceLineNumber;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getExpectedType() {
        return expectedType;
    }

    public void setExpectedType(String expectedType) {
        this.expectedType = expectedType;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
