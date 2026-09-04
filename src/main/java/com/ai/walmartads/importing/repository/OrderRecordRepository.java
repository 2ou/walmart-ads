package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    @Modifying
    @Query(value = """
            update wm_ads_order_record orders
            join wm_ads_sku_mapping mapping
              on mapping.sku = orders.sku
             and mapping.current_version = true
               set orders.upc = mapping.upc
             where orders.upc is null or trim(orders.upc) = ''
            """, nativeQuery = true)
    int backfillUpcFromCurrentMappings();

    /**
     * 批量删除指定导入文件版本关联的全部订单明细。
     *
     * <p>HISTORY 报表同时写入订单与广告两张明细表，故「整类型快照替换」需一并清理，
     * 否则旧版本会在订单侧同样滞留（历史上一度累积 71,315 行 = 14,263 × 5 的完全相同副本）。
     */
    @Modifying
    @Query("delete from OrderRecord o where o.importFile.id in :importFileIds")
    int deleteByImportFileIdIn(@Param("importFileIds") Collection<Long> importFileIds);
}
