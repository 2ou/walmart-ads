package com.ai.walmartads.pricemonitor.web;

import com.ai.walmartads.pricemonitor.PriceMonitorQueryService;
import com.ai.walmartads.pricemonitor.PriceMonitorService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 价格监控接口：右上角角标（summary）、按款分组变动列表（changes）、手动扫描（scan）、标记已处理（ack）。
 */
@RestController
@RequestMapping("/api/price-monitor")
public class PriceMonitorController {

    private final PriceMonitorQueryService queryService;
    private final PriceMonitorService monitorService;

    public PriceMonitorController(
            PriceMonitorQueryService queryService,
            PriceMonitorService monitorService
    ) {
        this.queryService = queryService;
        this.monitorService = monitorService;
    }

    /** 右上角角标数据：有活跃变动的款数 + 未确认红色数。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(queryService.summary());
    }

    /** 按款分组的变动列表（红色异常优先）。 */
    @GetMapping("/changes")
    public ApiResponse<Map<String, Object>> changes() {
        return ApiResponse.ok(queryService.changes());
    }

    /** 单款变动明细（下钻用）。 */
    @GetMapping("/style/{parentSku}")
    public ApiResponse<Map<String, Object>> styleDetail(@PathVariable("parentSku") String parentSku) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentSku", parentSku.toUpperCase());
        data.put("changes", queryService.styleChanges(parentSku));
        return ApiResponse.ok(data);
    }

    /** 全量变动历史（含已确认的红色异常、不受展示窗口限制），支持款号 / SKU / 最近天数过滤 + 分页。 */
    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(
            @RequestParam(required = false) String parentSku,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.history(parentSku, sku, days, page, size));
    }

    /** 单 SKU 价格走势（基线起点 → 各次变动后价格 → 当前价），供折线图使用。 */
    @GetMapping("/trend")
    public ApiResponse<Map<String, Object>> trend(@RequestParam String sku) {
        return ApiResponse.ok(queryService.trend(sku));
    }

    /** 手动触发一次价格比对（与每小时定时同口径），返回本次检测到的变动条数。 */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan() {
        int changes = monitorService.monitorOnce();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changes", changes);
        data.put("message", changes > 0 ? String.format("本次检测到 %d 条价格变动", changes)
                : "本次无价格变动");
        return ApiResponse.ok(data);
    }

    /** 标记一条变动为已处理（红色异常消失）。 */
    @PostMapping("/{id}/ack")
    public ApiResponse<Map<String, Object>> ack(@PathVariable("id") Long id) {
        monitorService.acknowledge(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return ApiResponse.ok("已标记为已处理，记录仍保留在历史中", data);
    }

    /** 批量标记一个款号下全部未处理的异常价格变动。 */
    @PostMapping("/style/{parentSku}/ack")
    public ApiResponse<Map<String, Object>> ackStyle(@PathVariable("parentSku") String parentSku) {
        int acknowledged = monitorService.acknowledgeStyle(parentSku);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentSku", parentSku.trim().toUpperCase());
        data.put("acknowledged", acknowledged);
        String message = acknowledged > 0
                ? String.format("已处理 %s 的 %d 条异常价格变动，记录仍保留在历史中",
                parentSku.trim().toUpperCase(), acknowledged)
                : "该款号没有待处理的异常变动";
        return ApiResponse.ok(message, data);
    }
}
