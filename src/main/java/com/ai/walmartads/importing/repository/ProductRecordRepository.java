package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ProductRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductRecordRepository extends JpaRepository<ProductRecord, Long> {

    @Modifying
    @Query("update ProductRecord product set product.currentVersion = false where product.sku in :skus")
    int deactivateCurrentVersions(@Param("skus") Collection<String> skus);

    List<ProductRecord> findByParentSku(String parentSku);
}
