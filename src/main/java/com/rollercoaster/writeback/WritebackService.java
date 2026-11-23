package com.rollercoaster.writeback;

import com.rollercoaster.config.RedisCacheAndPubSubConfig;
import com.rollercoaster.model.DualEngineCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.MessagingException;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(WritebackService.class);

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
                Instant.now());

        WorkflowEngine.ValidationOutcome validation = workflowEngine.validateSubmission(entity);
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                    "Write-back validation failed: " + String.join("; ", validation.errors()));
        }

        return writebackRepository.save(entity);
    }

    @Transactional
    public WritebackStagingEntity approveAndMerge(UUID stagingId, String compositeKeyIdentifier) {
        WritebackStagingEntity entity = writebackRepository.findById(stagingId)
                .orElseThrow(() -> new IllegalArgumentException("Staging record not found: " + stagingId));

        String versionKey = resolveVersionKey(entity, compositeKeyIdentifier);
        long currentVersion = recordVersions.getOrDefault(versionKey, entity.getExpectedVersion());
        if (currentVersion != entity.getExpectedVersion()) {
            throw new OptimisticLockingConflictException(
                    "Concurrent modification detected on target record " + versionKey
                            + ": expected version " + entity.getExpectedVersion()
                            + " but found version " + currentVersion,
                    entity.getExpectedVersion(),
                    currentVersion);
        }

        long nextVersion = currentVersion + 1;
        recordVersions.put(versionKey, nextVersion);
        entity.setStatus("APPROVED");
        WritebackStagingEntity saved = writebackRepository.save(entity);

        dualEngineCoordinator.invalidateTenantCache(entity.getTenantId());
        publishInvalidationEvent(entity, saved);
        broadcastStompUpdate(entity, saved, nextVersion);

        return saved;
    }

    public void setRecordVersionForTesting(String key, long version) {
        recordVersions.put(key, version);
    }

    public List<WritebackStagingEntity> listPending(String tenantId) {
        return writebackRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "PENDING");
    }

    private String resolveVersionKey(WritebackStagingEntity entity, String compositeKeyIdentifier) {
        if (compositeKeyIdentifier != null && !compositeKeyIdentifier.isBlank()) {
            return compositeKeyIdentifier;
        }
        return entity.getTargetTable() + ":" + entity.getRecordIdJson();
    }

    private void publishInvalidationEvent(WritebackStagingEntity entity, WritebackStagingEntity saved) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(
                    RedisCacheAndPubSubConfig.CHANNEL_WRITEBACK_EVENTS,
                    entity.getTenantId() + ":" + entity.getTargetTable() + ":" + saved.getId());
        } catch (RedisConnectionFailureException | IllegalStateException ex) {
            LOGGER.warn("Failed to publish write-back Redis invalidation event: {}", ex.getMessage());
        }
    }

    private void broadcastStompUpdate(
            WritebackStagingEntity entity,
            WritebackStagingEntity saved,
            long nextVersion) {
        if (messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend(
                    "/topic/writeback/" + entity.getTenantId(),
                    (Object) Map.of(
                            "stagingId", saved.getId().toString(),
                            "targetTable", saved.getTargetTable(),
                            "status", saved.getStatus(),
                            "newVersion", nextVersion));
        } catch (MessagingException | IllegalStateException ex) {
            LOGGER.warn("Failed to broadcast STOMP write-back update: {}", ex.getMessage());
        }
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
