package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {
}
