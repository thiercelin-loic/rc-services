package com.rollercoaster.writeback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WritebackRepository extends JpaRepository<WritebackStagingEntity, UUID> {
    List<WritebackStagingEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<WritebackStagingEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);
}
