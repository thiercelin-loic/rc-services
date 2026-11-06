package com.rollercoaster.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuarantineRepository extends JpaRepository<QuarantineRecord, UUID> {
    List<QuarantineRecord> findByTenantIdAndDatasetIdOrderBySourceLineNumberAsc(String tenantId, String datasetId);
}
