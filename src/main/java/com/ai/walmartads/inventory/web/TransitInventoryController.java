package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.TransitInventoryService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 路途渠道时效的人工维护入口。 */
@RestController
@RequestMapping("/api/transit")
public class TransitInventoryController {

    private final TransitInventoryService transitInventoryService;

    public TransitInventoryController(TransitInventoryService transitInventoryService) {
        this.transitInventoryService = transitInventoryService;
    }

    @PatchMapping("/channels/{channelName}")
    public ApiResponse<TransitInventoryService.TransitChannel> updateChannelLeadTime(
            @PathVariable String channelName,
            @RequestBody TransitInventoryService.LeadTimePatch input
    ) {
        return ApiResponse.ok(transitInventoryService.updateManualLeadDays(channelName, input.leadDays()));
    }
}
