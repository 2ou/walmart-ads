package com.ai.walmartads.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiAnalysisRepository extends JpaRepository<AiAnalysis, Long> {

    Optional<AiAnalysis> findByDataFingerprint(String dataFingerprint);

    Optional<AiAnalysis> findTopByStatusOrderByCreatedAtDesc(AiAnalysisStatus status);
}
