package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.ForecastFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ForecastFactorRepository extends JpaRepository<ForecastFactor, String> {
}
