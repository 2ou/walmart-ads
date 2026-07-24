package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.OrderRecord;

import java.util.List;

public record HistoricalWorkbookRows(
        int sourceRowCount,
        List<OrderRecord> orders,
        List<AdPerformance> ads
) {
}
