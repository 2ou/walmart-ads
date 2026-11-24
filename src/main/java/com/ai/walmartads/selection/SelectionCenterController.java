package com.ai.walmartads.selection;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/selection-center")
public class SelectionCenterController {
    private final SelectionCenterService service;

    public SelectionCenterController(SelectionCenterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> board(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(service.board(date, account()));
    }

    @GetMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<SelectionCenterService.Detail>> detail(@PathVariable String itemId,
                                                                             @RequestParam(required = false) LocalDate date) {
        var result = service.detail(itemId, date, account());
        return result == null ? ResponseEntity.status(404).body(new ApiResponse<>(false, "该期未记录此商品", null))
                : ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/items/{itemId}/watch")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> watch(@PathVariable String itemId, @RequestBody Watch value) {
        if (value == null || value.watched() == null) throw new IllegalArgumentException("需要指定关注状态");
        if (!service.watch(account(), itemId, value.watched())) {
            return ResponseEntity.status(404).body(new ApiResponse<>(false, "未找到该商品", null));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("watched", value.watched())));
    }

    @PostMapping("/snapshots")
    public ApiResponse<SelectionCenterService.ImportResult> ingest(@RequestBody SelectionSnapshot snapshot) {
        account();
        return ApiResponse.ok(service.ingest(snapshot));
    }

    private String account() {
        return WorkspaceContext.requirePrincipal().username();
    }

    public record Watch(Boolean watched) {}
}
