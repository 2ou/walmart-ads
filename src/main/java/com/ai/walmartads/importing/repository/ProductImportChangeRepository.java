package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ProductImportChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImportChangeRepository extends JpaRepository<ProductImportChange, Long> {
}
