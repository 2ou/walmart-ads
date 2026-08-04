package com.ai.walmartads.operations.web;

import com.ai.walmartads.operations.application.OperationRecordService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/operations")
public class OperationRecordController {

    private final OperationRecordService operationRecordService;

    public OperationRecordController(OperationRecordService operationRecordService) {
        this.operationRecordService = operationRecordService;
    }

    @GetMapping
    public ApiResponse<OperationRecordService.OperationPayload> list(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetKey,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(operationRecordService.list(
                days,
                startDate,
                endDate,
                targetType,
                targetKey,
                query,
                limit
        ));
    }

    @GetMapping("/targets")
    public ApiResponse<List<OperationRecordService.TargetOption>> targets(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(operationRecordService.targets(query, limit));
    }

    @PostMapping
    public ApiResponse<OperationRecordService.OperationRecord> create(
            @RequestBody CreateOperationRequest request
    ) {
        return ApiResponse.ok(
                "运营记录已保存；已执行记录会从下一业务日开始追踪效果。",
                operationRecordService.create(new OperationRecordService.CreateCommand(
                        request.targetType(),
                        request.targetKey(),
                        request.actionType(),
                        request.sourceType(),
                        request.status(),
                        request.occurredAt(),
                        request.title(),
                        request.beforeValue(),
                        request.afterValue(),
                        request.note(),
                        request.externalReference()
                ))
        );
    }

    public record CreateOperationRequest(
            String targetType,
            String targetKey,
            String actionType,
            String sourceType,
            String status,
            LocalDateTime occurredAt,
            String title,
            String beforeValue,
            String afterValue,
            String note,
            String externalReference
    ) {
    }
}
