package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.ParentDailySalesOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentDailySalesOverrideRepository extends JpaRepository<ParentDailySalesOverride, String> {
}
