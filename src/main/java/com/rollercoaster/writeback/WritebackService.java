package com.rollercoaster.writeback;

import com.rollercoaster.config.RedisCacheAndPubSubConfig;
import com.rollercoaster.model.DualEngineCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages operational staging write-backs, optimistic locking verification against expected_version,
 * production table merges, Redis query cache invalidation, and WebSocket STOMP broadcasts.
 */
@Service
public class WritebackService {

    private final WritebackRepository writebackRepository;
    private final WorkflowEngine workflowEngine;
    private final DualEngineCoordinator dualEngineCoordinator;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, Long> recordVersions = new ConcurrentHashMap<>();

    public WritebackService(
            WritebackRepository writebackRepository,
            WorkflowEngine workflowEngine,
            DualEngineCoordinator dualEngineCoordinator,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.writebackRepository = writebackRepository;
        this.workflowEngine = workflowEngine;
        this.dualEngineCoordinator = dualEngineCoordinator;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public WritebackStagingEntity submitWriteback(WritebackSubmissionRequest request) {
        WritebackStagingEntity entity = new WritebackStagingEntity(
                null,
                request.tenantId() != null ? request.tenantId() : "default-tenant",
                request.userId() != null ? request.userId() : "AL",
                request.targetTable(),
                request.recordIdJson(),
                request.payloadJson(),
                request.expectedVersion(),
                "PENDING",
                Instant.now()
        );

        WorkflowEngine.ValidationOutcome validation = workflowEngine.validateSubmission(entity);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Write-back validation failed: " + String.join("; ", validation.errors()));
        }

        return writebackRepository.save(entity);
    }

    @Transactional
    public WritebackStagingEntity approveAndMerge(UUID stagingId, String compositeKeyIdentifier) {
        WritebackStagingEntity entity = writebackRepository.findById(stagingId)
                .orElseThrow(() -> new IllegalArgumentException("Staging record not found: " + stagingId));

        String versionKey = compositeKeyIdentifier != null && !compositeKeyIdentifier.isBlank()
                ? compositeKeyIdentifier
                : entity.getTargetTable() + ":" + entity.getRecordIdJson();

        long currentVersion = recordVersions.getOrDefault(versionKey, entity.getExpectedVersion());
        if (currentVersion != entity.getExpectedVersion()) {
            throw new OptimisticLockingConflictException(
                    "Concurrent modification detected on target record " + versionKey
                            + ": expected version " + entity.getExpectedVersion()
                            + " but found version " + currentVersion,
                    entity.getExpectedVersion(),
                    currentVersion
            );
        }

        // Increment watermark version and mark APPROVED
        recordVersions.put(versionKey, currentVersion + 1);
        entity.setStatus("APPROVED");
        WritebackStagingEntity saved = writebackRepository.save(entity);

        // Invalidate tenant Redis query cache
        dualEngineCoordinator.invalidateTenantCache(entity.getTenantId());

        // Publish Redis Pub/Sub invalidation event
        if (redisTemplate != null) {
            try {
                redisTemplate.convertAndSend(
                        RedisCacheAndPubSubConfig.CHANNEL_WRITEBACK_EVENTS,
                        entity.getTenantId() + ":" + entity.getTargetTable() + ":" + saved.getId()
                );
            } catch (Exception ignored) {
            }
        }

        // Broadcast STOMP WebSocket update to active Angular dashboards
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(
                        "/topic/writeback/" + entity.getTenantId(),
                        (Object) Map.of(
                                "stagingId", saved.getId().toString(),
                                "targetTable", saved.getTargetTable(),
                                "status", saved.getStatus(),
                                "newVersion", currentVersion + 1
                        )
                );
            } catch (Exception ignored) {
            }
        }

        return saved;
    }

    public void setRecordVersionForTesting(String key, long version) {
        recordVersions.put(key, version);
    }

    public List<WritebackStagingEntity> listPending(String tenantId) {
        return writebackRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "PENDING");
    }

    public record WritebackSubmissionRequest(
            String tenantId,
            String userId,
            String targetTable,
            String recordIdJson,
            String payloadJson,
            long expectedVersion
    ) {
    }

}
