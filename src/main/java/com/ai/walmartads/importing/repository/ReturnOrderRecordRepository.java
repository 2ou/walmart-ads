package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnOrderRecordRepository extends JpaRepository<ReturnOrderRecord, Long> {
}
