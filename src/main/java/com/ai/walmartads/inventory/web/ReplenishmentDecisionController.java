package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ReplenishmentDecisionService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/forecast/decisions")
public class ReplenishmentDecisionController {

    private final ReplenishmentDecisionService service;

    public ReplenishmentDecisionController(ReplenishmentDecisionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ReplenishmentDecisionService.DecisionView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PatchMapping("/{parentSku}")
    public ApiResponse<ReplenishmentDecisionService.DecisionView> update(
            @PathVariable String parentSku,
            @RequestBody ReplenishmentDecisionService.DecisionPatch input
    ) {
        return ApiResponse.ok(service.update(parentSku, input));
    }
}
