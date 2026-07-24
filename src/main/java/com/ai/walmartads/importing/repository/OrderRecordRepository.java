package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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
}
