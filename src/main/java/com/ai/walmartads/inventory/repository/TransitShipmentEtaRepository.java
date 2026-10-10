package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.TransitShipmentEta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransitShipmentEtaRepository extends JpaRepository<TransitShipmentEta, String> {
}
