package com.ai.walmartads.sales.web;

import com.ai.walmartads.sales.application.SalesDataService;
import com.ai.walmartads.sales.application.SalesOrderFlowService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesDataService salesDataService;
    private final SalesOrderFlowService salesOrderFlowService;

    public SalesController(SalesDataService salesDataService, SalesOrderFlowService salesOrderFlowService) {
        this.salesDataService = salesDataService;
        this.salesOrderFlowService = salesOrderFlowService;
    }

    @GetMapping("/data")
    public ApiResponse<SalesDataService.SalesPayload> data(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.ok(salesDataService.load(days, startDate, endDate));
    }

    /**
     * 成交流水（订单行级明细，服务端分页）。
     * 默认窗口为最近成交日往前 30 天；pageSize 仅接受 20 / 50 / 100。
     */
    @GetMapping("/order-detail")
    public ApiResponse<SalesOrderFlowService.OrderFlowPayload> orderDetail(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        return ApiResponse.ok(salesOrderFlowService.load(startDate, endDate, keyword, page, pageSize, sort, direction));
    }
}
