package com.ai.walmartads.product.application;

import com.ai.walmartads.product.domain.ProfitAssumption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfitAssumptionRepository extends JpaRepository<ProfitAssumption, String> {
}
