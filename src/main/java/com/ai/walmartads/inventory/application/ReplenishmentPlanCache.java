package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ImportFileType;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/** Keeps the last completed plan available while imports and recalculations run. */
@Service
public class ReplenishmentPlanCache {

    private static final Set<ImportFileType> RELEVANT_IMPORTS = EnumSet.of(
            ImportFileType.ORDER,
            ImportFileType.INVENTORY,
            ImportFileType.PRODUCT,
            ImportFileType.PRODUCTION_ORDER,
            ImportFileType.SHIPMENT,
            ImportFileType.SHIPMENT_LOGISTICS,
            ImportFileType.DOMESTIC_INVENTORY
    );

    private final ReplenishmentPlanningService planningService;
    private volatile ReplenishmentPlanningService.PlanningPayload latest;

    public ReplenishmentPlanCache(ReplenishmentPlanningService planningService) {
        this.planningService = planningService;
    }

    public ReplenishmentPlanningService.PlanningPayload current() {
        ReplenishmentPlanningService.PlanningPayload snapshot = latest;
        return snapshot == null ? refresh() : snapshot;
    }

    public synchronized ReplenishmentPlanningService.PlanningPayload refresh() {
        ReplenishmentPlanningService.PlanningPayload calculated = planningService.plan();
        latest = calculated;
        return calculated;
    }

    public void refreshAfterSuccessfulImport(ImportFileType type) {
        if (RELEVANT_IMPORTS.contains(type)) {
            refresh();
        }
    }
}
