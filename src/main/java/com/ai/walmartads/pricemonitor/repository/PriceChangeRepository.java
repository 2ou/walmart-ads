package com.ai.walmartads.pricemonitor.repository;

import com.ai.walmartads.pricemonitor.domain.PriceChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PriceChangeRepository extends JpaRepository<PriceChange, Long>, JpaSpecificationExecutor<PriceChange> {

    /** 所有未确认的异常变动：异常必须保留到人工处理，不能随普通展示窗口消失。 */
    List<PriceChange> findByAcknowledgedFalseAndAbnormalTrueOrderByDetectedAtDesc();

    /** 窗口内的绿色（正常）变动：绿色不进确认流程，仅按时间窗展示。 */
    List<PriceChange> findByAbnormalFalseAndDetectedAtAfterOrderByDetectedAtDesc(LocalDateTime from);

    /** 批量确认一个款号下全部尚未处理的异常价格变动。 */
    @Modifying
    @Query("update PriceChange c set c.acknowledged = true "
            + "where upper(c.parentSku) = upper(:parentSku) "
            + "and c.abnormal = true and c.acknowledged = false")
    int acknowledgeAbnormalByParentSku(@Param("parentSku") String parentSku);

    /** 保留期清理：只删除普通变动或已处理异常；未处理异常必须继续留在工作台。 */
    @Modifying
    @Query("delete from PriceChange c where c.detectedAt < :cutoff "
            + "and (c.abnormal = false or c.acknowledged = true)")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 单 SKU 的全部变动（时间升序），用于拼接价格走势。 */
    List<PriceChange> findBySkuOrderByDetectedAtAsc(String sku);
}
