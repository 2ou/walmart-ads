package com.ai.walmartads.walmartlisting.repository;

import com.ai.walmartads.walmartlisting.domain.WalmartListingSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalmartListingSyncLogRepository extends JpaRepository<WalmartListingSyncLog, Long> {

    Optional<WalmartListingSyncLog> findTopByOrderByStartedAtDesc();
}
