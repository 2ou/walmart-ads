package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ProductRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRecordRepository extends JpaRepository<ProductRecord, Long> {

    @Modifying
    @Query("update ProductRecord product set product.currentVersion = false where product.sku in :skus")
    int deactivateCurrentVersions(@Param("skus") Collection<String> skus);

    List<ProductRecord> findByParentSku(String parentSku);

    List<ProductRecord> findAllBySkuInAndCurrentVersionTrue(Collection<String> skus);

    @Query(value = """
            select parent_sku, cutting_yield
              from wm_ads_product_manual_setting
             where workspace_id = :workspaceId
               and parent_sku in (:parentSkus)
            """, nativeQuery = true)
    List<Object[]> findManualCuttingYields(
            @Param("workspaceId") long workspaceId,
            @Param("parentSkus") Collection<String> parentSkus
    );

    Optional<ProductRecord> findFirstByProductIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(String productId);

    Optional<ProductRecord> findFirstBySkuAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(String sku);
}
