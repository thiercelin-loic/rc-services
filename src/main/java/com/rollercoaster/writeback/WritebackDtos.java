package com.rollercoaster.writeback;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO contracts for write-back staging operations,
 * decoupling persistence entities from web client representations.
 */
public final class WritebackDtos {

    private WritebackDtos() {}

    public record WritebackStagingDto(
            UUID id,
            String tenantId,
            String userId,
            String targetTable,
            String recordIdJson,
            String payloadJson,
            long expectedVersion,
            String status,
            Instant createdAt
    ) {
        public static WritebackStagingDto from(WritebackStagingEntity entity) {
            if (entity == null) {
                return null;
            }
            return new WritebackStagingDto(
                    entity.getId(),
                    entity.getTenantId(),
                    entity.getUserId(),
                    entity.getTargetTable(),
                    entity.getRecordIdJson(),
                    entity.getPayloadJson(),
                    entity.getExpectedVersion(),
                    entity.getStatus(),
                    entity.getCreatedAt()
            );
        }
    }
}

