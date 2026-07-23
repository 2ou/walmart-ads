package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ImportBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Page<ImportBatch> findAllByOrderByStartedAtDesc(Pageable pageable);
}
