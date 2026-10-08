package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastSettingService;
import com.ai.walmartads.inventory.application.ReplenishmentPlanCache;
import com.ai.walmartads.inventory.application.ReplenishmentPlanningService;
import com.ai.walmartads.shared.api.ApiResponse;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecast/recommendations")
public class ReplenishmentPlanningController {

    private final ReplenishmentPlanCache cache;
    private final ForecastSettingService forecastSettingService;

    public ReplenishmentPlanningController(ReplenishmentPlanCache cache,
                                           ForecastSettingService forecastSettingService) {
        this.cache = cache;
        this.forecastSettingService = forecastSettingService;
    }

    /** 当前保存的全局目标覆盖天数，供前端启动时回填输入框。 */
    @GetMapping("/setting")
    public ApiResponse<Map<String, Object>> setting() {
        return ApiResponse.ok(Map.of("targetDays", forecastSettingService.getTargetDays()));
    }

    @GetMapping
    public ApiResponse<ReplenishmentPlanningService.PlanningPayload> plan(
            @RequestParam(required = false) Integer targetDays) {
        return ApiResponse.ok(cache.current(forecastSettingService.resolve(targetDays)));
    }

    @PostMapping("/recalculate")
    public ApiResponse<ReplenishmentPlanningService.PlanningPayload> recalculate(
            @RequestParam(required = false) Integer targetDays) {
        int effective = forecastSettingService.resolve(targetDays);
        // 显式传入的新天数即「确认保存」：先落库，后续接口在未传参时也会用这个值
        if (targetDays != null && targetDays > 0) {
            effective = forecastSettingService.saveTargetDays(effective);
        }
        return ApiResponse.ok(cache.refresh(effective));
    }
}
