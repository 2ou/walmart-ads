package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.DomesticInventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomesticInventorySnapshotRepository extends JpaRepository<DomesticInventorySnapshot, Long> {
}
