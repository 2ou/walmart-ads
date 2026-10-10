package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.TransitInventoryService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 路途渠道时效与货件到达日期的人工维护入口。 */
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

    /** 人工补充 / 清空某货件的到达日期；arrivalDate 传 null 或空串表示清空。 */
    @PatchMapping("/shipments/{shipmentNo}/arrival")
    public ApiResponse<Void> updateShipmentArrival(
            @PathVariable String shipmentNo,
            @RequestBody TransitInventoryService.ArrivalPatch input
    ) {
        transitInventoryService.updateManualArrival(shipmentNo, input.arrivalDate());
        return ApiResponse.ok(null);
    }
}
