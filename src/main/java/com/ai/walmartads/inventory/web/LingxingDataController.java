package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.LingxingDataService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 领星数据模块接口。只读，数据来自已同步的业务库表，不在浏览时回源领星。
 */
@RestController
@RequestMapping("/api/lingxing-data")
public class LingxingDataController {

    private final LingxingDataService lingxingDataService;

    public LingxingDataController(LingxingDataService lingxingDataService) {
        this.lingxingDataService = lingxingDataService;
    }

    @GetMapping("/domestic")
    public ApiResponse<LingxingDataService.DomesticPayload> domestic() {
        return ApiResponse.ok(lingxingDataService.domestic());
    }

    @GetMapping("/wfs-shipments")
    public ApiResponse<LingxingDataService.WfsShipmentPayload> wfsShipments() {
        return ApiResponse.ok(lingxingDataService.wfsShipments());
    }

    @GetMapping("/wfs-shipments/{shipmentNo}")
    public ApiResponse<LingxingDataService.WfsShipmentDetail> wfsShipmentDetail(@PathVariable String shipmentNo) {
        LingxingDataService.WfsShipmentDetail detail = lingxingDataService.wfsShipmentDetail(shipmentNo);
        return detail == null
                ? new ApiResponse<>(false, "货件不存在或已不在当前快照中", null)
                : ApiResponse.ok(detail);
    }

    @GetMapping("/sync-logs")
    public ApiResponse<List<LingxingDataService.SyncLogEntry>> syncLogs() {
        return ApiResponse.ok(lingxingDataService.syncLogs());
    }
}
