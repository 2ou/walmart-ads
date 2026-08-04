package com.ai.walmartads.sales.web;

import com.ai.walmartads.sales.application.SalesDataService;
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

    public SalesController(SalesDataService salesDataService) {
        this.salesDataService = salesDataService;
    }

    @GetMapping("/data")
    public ApiResponse<SalesDataService.SalesPayload> data(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.ok(salesDataService.load(days, startDate, endDate));
    }
}
