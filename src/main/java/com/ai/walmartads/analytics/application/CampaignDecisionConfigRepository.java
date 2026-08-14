package com.ai.walmartads.analytics.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CampaignDecisionConfigRepository extends JpaRepository<CampaignDecisionConfig, String> {

    Optional<CampaignDecisionConfig> findByCampaignType(String campaignType);
}
