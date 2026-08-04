package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ParentLifecycleService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecast/lifecycle")
public class ParentLifecycleController {

    private final ParentLifecycleService service;

    public ParentLifecycleController(ParentLifecycleService service) {
        this.service = service;
    }

    @PatchMapping("/{parentSku}")
    public ApiResponse<ParentLifecycleService.LifecycleView> update(
            @PathVariable String parentSku,
            @RequestBody LifecyclePatch patch
    ) {
        if (patch == null || (patch.clearance() == null && patch.allSeason() == null && patch.seasonType() == null)) {
            throw new IllegalArgumentException("clearance, allSeason or seasonType is required");
        }
        String seasonType = patch.seasonType();
        if (seasonType == null && patch.allSeason() != null) {
            seasonType = patch.allSeason() ? "ALL_SEASON" : "SEASONAL";
        }
        return ApiResponse.ok(service.update(parentSku, patch.clearance(), seasonType));
    }

    public record LifecyclePatch(Boolean clearance, Boolean allSeason, String seasonType) {
    }
}
