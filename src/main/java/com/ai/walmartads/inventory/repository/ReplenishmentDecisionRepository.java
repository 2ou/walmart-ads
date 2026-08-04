package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.ReplenishmentDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplenishmentDecisionRepository extends JpaRepository<ReplenishmentDecision, String> {
}
