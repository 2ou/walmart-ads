package com.ai.walmartads.operations.web;

import com.ai.walmartads.operations.application.OperationRecordService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/operations")
public class OperationRecordController {

    private final OperationRecordService operationRecordService;

    public OperationRecordController(OperationRecordService operationRecordService) {
        this.operationRecordService = operationRecordService;
    }

    /**
     * 运营记录看板：按款分组，组内为「复盘任务 › 运营记录」。
     */
    @GetMapping("/board")
    public ApiResponse<OperationRecordService.BoardPayload> board(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return ApiResponse.ok(operationRecordService.board(days, query, limit));
    }

    @GetMapping
    public ApiResponse<OperationRecordService.OperationPayload> list(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetKey,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(operationRecordService.list(
                days,
                targetType,
                targetKey,
                query,
                limit
        ));
    }

    /**
     * 运营记录的款号 / SKU 候选池（数据源 = 产品库在售清单）。
     *
     * @param targetType {@code PARENT_SKU} / {@code SKU}；留空 = 两类都返回
     */
    @GetMapping("/targets")
    public ApiResponse<List<OperationRecordService.TargetOption>> targets(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(operationRecordService.targets(targetType, query, limit));
    }

    /**
     * 款内 SKU 候选：新增运营记录时勾选作用范围（默认全选 = 整款）。
     */
    @GetMapping("/targets/skus")
    public ApiResponse<List<OperationRecordService.TargetSkuOption>> targetSkus(
            @RequestParam String parentSku
    ) {
        return ApiResponse.ok(operationRecordService.skusOfParentOptions(parentSku));
    }

    /**
     * 复盘任务列表（可按款号过滤），供新增记录时选择归属任务。
     */
    @GetMapping("/tasks")
    public ApiResponse<List<OperationRecordService.TaskBrief>> tasks(
            @RequestParam(required = false) String parentSku
    ) {
        return ApiResponse.ok(operationRecordService.listTasks(parentSku));
    }

    @PostMapping("/tasks")
    public ApiResponse<OperationRecordService.ReviewTask> createTask(
            @RequestBody CreateTaskRequest request
    ) {
        return ApiResponse.ok(
                "复盘任务已创建，检查点会按设定天数提醒你复盘。",
                operationRecordService.createTask(new OperationRecordService.CreateTaskCommand(
                        request.parentSku(),
                        request.title(),
                        request.goal(),
                        request.startedAt(),
                        request.checkpoints() == null ? null : request.checkpoints().stream()
                                .map(checkpoint -> new OperationRecordService.Checkpoint(
                                        checkpoint.afterDays(), checkpoint.label()))
                                .toList()
                ))
        );
    }

    /**
     * 结束整个复盘任务，结论必填。
     */
    @PostMapping("/tasks/{id}/close")
    public ApiResponse<OperationRecordService.ReviewTask> closeTask(
            @PathVariable long id,
            @RequestBody(required = false) CloseTaskRequest request
    ) {
        return ApiResponse.ok(
                "复盘任务已结束，结论已沉淀到该款。",
                operationRecordService.closeTask(id, request == null ? null : request.conclusion())
        );
    }

    @PostMapping
    public ApiResponse<OperationRecordService.OperationRecord> create(
            @RequestBody CreateOperationRequest request
    ) {
        return ApiResponse.ok(
                "运营记录已保存；已执行记录会从下一业务日开始追踪效果。",
                operationRecordService.create(new OperationRecordService.CreateCommand(
                        request.reviewTaskId(),
                        request.targetType(),
                        request.targetKey(),
                        request.targetKeys(),
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

    /**
     * 复盘检查点（挂在任务上）。scope=due 只看已到期未复盘，scope=all 看全部。
     */
    @GetMapping("/reminders")
    public ApiResponse<OperationRecordService.ReminderPayload> reminders(
            @RequestParam(required = false) String parentSku,
            @RequestParam(required = false, defaultValue = "due") String scope
    ) {
        return ApiResponse.ok(operationRecordService.listReminders(parentSku, scope));
    }

    @PostMapping("/reminders/{id}/resolve")
    public ApiResponse<Void> resolveReminder(@PathVariable long id) {
        operationRecordService.resolveReminder(id);
        return ApiResponse.ok("已标记为已复盘。", (Void) null);
    }

    public record Checkpoint(
            Integer afterDays,
            String label
    ) {
    }

    public record CreateOperationRequest(
            Long reviewTaskId,
            String targetType,
            String targetKey,
            List<String> targetKeys,
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

    public record CreateTaskRequest(
            String parentSku,
            String title,
            String goal,
            LocalDateTime startedAt,
            List<Checkpoint> checkpoints
    ) {
    }

    public record CloseTaskRequest(
            String conclusion
    ) {
    }
}
