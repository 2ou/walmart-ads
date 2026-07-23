package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.AdPerformance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdPerformanceRepository extends JpaRepository<AdPerformance, Long> {
}
