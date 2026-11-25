package com.ai.walmartads.finance;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.finance.FinanceCommissionRuleService.Rule;
import com.ai.walmartads.finance.FinanceCommissionRuleService.RuleInput;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceAnalyticsService analyticsService;
    private final FinanceCommissionRuleService commissionRuleService;

    public FinanceController(FinanceAnalyticsService analyticsService,
                             FinanceCommissionRuleService commissionRuleService) {
        this.analyticsService = analyticsService;
        this.commissionRuleService = commissionRuleService;
    }

    @GetMapping("/overview")
    public ApiResponse<FinanceAnalyticsService.OverviewPayload> overview(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(defaultValue = "true") boolean includeEstimate,
            @RequestParam(required = false) LocalDate customStartDate,
            @RequestParam(required = false) LocalDate customEndDate,
            @RequestParam(required = false) BigDecimal exchangeRate,
            @RequestParam(required = false) BigDecimal offsiteFeeCny
    ) {
        return ApiResponse.ok(analyticsService.overview(
                startMonth,
                endMonth,
                includeEstimate,
                customStartDate,
                customEndDate,
                exchangeRate,
                offsiteFeeCny
        ));
    }

    @GetMapping("/styles/{parentSku}")
    public ApiResponse<FinanceAnalyticsService.StyleDetailPayload> styleDetail(
            @PathVariable String parentSku,
            @RequestParam(defaultValue = "true") boolean includeEstimate,
            @RequestParam(required = false) String targetEstimateMonth,
            @RequestParam(required = false) LocalDate customStartDate,
            @RequestParam(required = false) LocalDate customEndDate,
            @RequestParam(required = false) BigDecimal exchangeRate,
            @RequestParam(required = false) BigDecimal offsiteFeeCny
    ) {
        return ApiResponse.ok(analyticsService.styleDetail(parentSku, includeEstimate, targetEstimateMonth,
                customStartDate, customEndDate, exchangeRate, offsiteFeeCny));
    }

    @GetMapping("/commission-rules")
    public ApiResponse<List<Rule>> commissionRules() {
        return ApiResponse.ok(commissionRuleService.list());
    }

    @PostMapping("/commission-rules")
    public ApiResponse<List<Rule>> saveCommissionRules(@RequestBody List<RuleInput> rules) {
        return ApiResponse.ok(commissionRuleService.save(rules));
    }

    @DeleteMapping("/commission-rules/{id}")
    public ApiResponse<List<Rule>> removeCommissionRule(@PathVariable long id) {
        return ApiResponse.ok(commissionRuleService.remove(id));
    }
}
