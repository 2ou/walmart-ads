package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;

public enum ImportSource {
    ORDER,
    ITEM_PERFORMANCE,
    ITEM_KEYWORD_PERFORMANCE,
    KEYWORD_PERFORMANCE,
    INVENTORY,
    WEEKLY_INVENTORY,
    PRODUCT,
    RETURN_ORDER,
    SKU_MAPPING,
    HISTORY,
    ITEM_REPORT,
    DEMAND_FORECAST,
    PRODUCTION_ORDER,
    SHIPMENT,
    SHIPMENT_LOGISTICS,
    DOMESTIC_INVENTORY;

    public boolean matches(SourceFileCandidate candidate) {
        return switch (this) {
            case ORDER -> candidate.fileType() == ImportFileType.ORDER;
            case ITEM_PERFORMANCE -> matchesAd(candidate, AdReportKind.ITEM_PERFORMANCE);
            case ITEM_KEYWORD_PERFORMANCE -> matchesAd(
                    candidate,
                    AdReportKind.ITEM_KEYWORD_PERFORMANCE
            );
            case KEYWORD_PERFORMANCE -> matchesAd(candidate, AdReportKind.KEYWORD_PERFORMANCE);
            case INVENTORY -> candidate.fileType() == ImportFileType.INVENTORY;
            case WEEKLY_INVENTORY -> candidate.fileType() == ImportFileType.WEEKLY_INVENTORY;
            case PRODUCT -> candidate.fileType() == ImportFileType.PRODUCT;
            case RETURN_ORDER -> candidate.fileType() == ImportFileType.RETURN_ORDER;
            case SKU_MAPPING -> candidate.fileType() == ImportFileType.SKU_MAPPING;
            case HISTORY -> candidate.fileType() == ImportFileType.HISTORY;
            case ITEM_REPORT -> candidate.fileType() == ImportFileType.ITEM_REPORT;
            case DEMAND_FORECAST -> candidate.fileType() == ImportFileType.DEMAND_FORECAST;
            case PRODUCTION_ORDER -> candidate.fileType() == ImportFileType.PRODUCTION_ORDER;
            case SHIPMENT -> candidate.fileType() == ImportFileType.SHIPMENT;
            case SHIPMENT_LOGISTICS -> candidate.fileType() == ImportFileType.SHIPMENT_LOGISTICS;
            case DOMESTIC_INVENTORY -> candidate.fileType() == ImportFileType.DOMESTIC_INVENTORY;
        };
    }

    private static boolean matchesAd(
            SourceFileCandidate candidate,
            AdReportKind reportKind
    ) {
        return candidate.fileType() == ImportFileType.AD_REPORT
                && candidate.reportKind() == reportKind;
    }
}
