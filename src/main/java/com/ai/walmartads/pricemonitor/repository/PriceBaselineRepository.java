package com.ai.walmartads.pricemonitor.repository;

import com.ai.walmartads.pricemonitor.domain.PriceBaseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceBaselineRepository extends JpaRepository<PriceBaseline, String> {

    Optional<PriceBaseline> findBySku(String sku);
}
