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

    List<ProductRecord> findAllByCurrentVersionTrueOrderBySkuAsc();

    List<ProductRecord> findAllBySkuInOrderByCreatedAtDescIdDesc(Collection<String> skus);

    @Modifying
    @Query("update ProductRecord product set product.currentVersion = false where product.currentVersion = true and product.id in :ids")
    int deactivateCurrentVersionsById(@Param("ids") Collection<Long> ids);

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

    interface SkuPriceView {
        String getSku();
        String getParentSku();
        java.math.BigDecimal getPrice();
        java.time.LocalDateTime getSourceUpdatedAt();
    }

    @Query("""
            select product.sku as sku,
                   product.parentSku as parentSku,
                   product.price as price,
                   product.sourceUpdatedAt as sourceUpdatedAt
              from ProductRecord product
             where product.currentVersion = true
               and product.sku is not null
               and product.price is not null
               and product.price > 0
            """)
    List<SkuPriceView> findCurrentSkuPrices();

    interface ParentReviewSummaryView {
        String getParentSku();
        Integer getReviewCount();
        java.math.BigDecimal getAverageRating();
    }

    @Query("""
            select product.parentSku as parentSku,
                   product.reviewCount as reviewCount,
                   product.averageRating as averageRating
              from ProductRecord product
             where product.currentVersion = true
               and product.parentSku is not null
            """)
    List<ParentReviewSummaryView> findCurrentReviewSummaries();
}
