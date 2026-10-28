package com.ai.walmartads.connector;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Public only on loopback; every operation is authenticated with a workspace-scoped bridge key. */
@RestController
@RequestMapping("/api/browser-bridge")
public class BrowserBridgeController {

    private final CurrentBrowserBridgeService bridge;

    public BrowserBridgeController(CurrentBrowserBridgeService bridge) {
        this.bridge = bridge;
    }

    @PostMapping("/heartbeat")
    public ApiResponse<CurrentBrowserBridgeService.BridgeStatus> heartbeat(HttpServletRequest request) {
        WorkspacePrincipal principal = authenticate(request);
        return ApiResponse.ok(bridge.heartbeat(principal));
    }

    @PostMapping("/credential")
    public ApiResponse<CurrentBrowserBridgeService.BridgeStatus> credential(
            HttpServletRequest request, @RequestBody CredentialRequest body) {
        WorkspacePrincipal principal = authenticate(request);
        bridge.saveSupplyCredential(principal, body == null ? null : body.value());
        return ApiResponse.ok(bridge.heartbeat(principal));
    }

    @PostMapping("/supply-sync")
    public ApiResponse<CurrentBrowserBridgeService.SyncResult> supplySync(HttpServletRequest request) {
        WorkspacePrincipal principal = authenticate(request);
        return ApiResponse.ok(bridge.syncSupply(principal, "CURRENT_CHROME"));
    }

    @PostMapping("/lingxing-inventory")
    public ApiResponse<CurrentBrowserBridgeService.SyncResult> inventory(
            HttpServletRequest request, @RequestBody SnapshotRequest body) {
        WorkspacePrincipal principal = authenticate(request);
        requireSnapshot(body);
        return ApiResponse.ok(bridge.importInventory(principal, body.sourceUrl(), body.response(),
                capturedAt(body.capturedAt()), "CURRENT_CHROME", body.requestBody()));
    }

    @PostMapping("/lingxing-wfs-shipments")
    public ApiResponse<CurrentBrowserBridgeService.SyncResult> wfs(
            HttpServletRequest request, @RequestBody SnapshotRequest body) {
        WorkspacePrincipal principal = authenticate(request);
        requireSnapshot(body);
        return ApiResponse.ok(bridge.importWfs(principal, body.sourceUrl(), body.response(),
                capturedAt(body.capturedAt()), "CURRENT_CHROME"));
    }

    @PostMapping("/failure")
    public ApiResponse<Void> failure(HttpServletRequest request, @RequestBody FailureRequest body) {
        WorkspacePrincipal principal = authenticate(request);
        if (body == null || body.source() == null || body.source().isBlank()) {
            throw new IllegalArgumentException("缺少数据源");
        }
        ConnectorSource source;
        try {
            source = ConnectorSource.valueOf(body.source().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的数据源");
        }
        bridge.recordFailure(principal, source,
                body.message() == null || body.message().isBlank() ? "当前 Chrome 同步失败" : body.message(),
                body.loginRequired());
        return ApiResponse.ok(null);
    }

    private WorkspacePrincipal authenticate(HttpServletRequest request) {
        return bridge.authenticateAndReload(request.getHeader("X-Bridge-Key"),
                request.getHeader("X-Extension-Id"));
    }

    private static void requireSnapshot(SnapshotRequest body) {
        if (body == null || body.response() == null || body.sourceUrl() == null || body.sourceUrl().isBlank()) {
            throw new IllegalArgumentException("同步快照不完整");
        }
    }

    private static LocalDateTime capturedAt(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                return LocalDateTime.now();
            }
        }
    }

    @ExceptionHandler(CurrentBrowserBridgeService.BridgeAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> authenticationError(RuntimeException exception) {
        return ApiResponse.error(exception.getMessage());
    }

    public record CredentialRequest(String value) {
    }

    /**
     * @param requestBody 扩展重放时使用的真实请求体（原文），仅用于排查筛选条件，不参与入库。
     */
    public record SnapshotRequest(String sourceUrl, JsonNode response, String capturedAt, String requestBody) {
    }

    public record FailureRequest(String source, String message, boolean loginRequired) {
    }
}
