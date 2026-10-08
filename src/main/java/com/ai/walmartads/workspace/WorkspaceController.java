package com.ai.walmartads.workspace;

import com.ai.walmartads.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceAccountService accounts;
    private final WorkspaceOwnershipService ownership;

    public WorkspaceController(WorkspaceAccountService accounts, WorkspaceOwnershipService ownership) {
        this.accounts = accounts;
        this.ownership = ownership;
    }

    @GetMapping("/me")
    public ApiResponse<WorkspaceAccountService.AccountView> me() {
        return ApiResponse.ok(accounts.currentAccount(false));
    }

    @PostMapping("/accounts")
    public ApiResponse<WorkspaceAccountService.SetupResult> createAccount(
            @RequestBody WorkspaceAccountService.CreateAccountRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(accounts.createAccount(request, WorkspaceAuthController.clientIp(servletRequest),
                servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/discover")
    public ApiResponse<List<WorkspaceAccountService.StoreOption>> discover(
            @RequestBody(required = false) WorkspaceAccountService.DiscoverRequest request
    ) {
        return ApiResponse.ok(accounts.discover(request));
    }

    @PostMapping("/binding")
    public ApiResponse<WorkspaceAccountService.AccountView> updateBinding(
            @RequestBody WorkspaceAccountService.StoreBindingRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(accounts.updateMcp(request, WorkspaceAuthController.clientIp(servletRequest),
                servletRequest.getHeader("User-Agent")));
    }

    @GetMapping("/mcp-key")
    public ApiResponse<Map<String, String>> revealKey(
            @RequestParam(defaultValue = "view") String purpose,
            HttpServletRequest servletRequest
    ) {
        String key = accounts.revealMcpKey(purpose, WorkspaceAuthController.clientIp(servletRequest),
                servletRequest.getHeader("User-Agent"));
        return ApiResponse.ok(Map.of("key", key == null ? "" : key));
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestBody WorkspaceAccountService.ChangePasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        accounts.changePassword(request, WorkspaceAuthController.clientIp(servletRequest),
                servletRequest.getHeader("User-Agent"));
        return ApiResponse.ok("密码已修改，现有会话保持有效", null);
    }

    @GetMapping("/managed-accounts")
    public ApiResponse<List<WorkspaceAccountService.ManagedAccountView>> managedAccounts() {
        return ApiResponse.ok(accounts.managedAccounts());
    }

    @PostMapping("/managed-accounts/{workspaceId}/password")
    public ApiResponse<Void> resetManagedPassword(
            @PathVariable long workspaceId,
            @RequestBody WorkspaceAccountService.PasswordResetRequest request,
            HttpServletRequest servletRequest
    ) {
        accounts.resetManagedPassword(workspaceId, request, WorkspaceAuthController.clientIp(servletRequest),
                servletRequest.getHeader("User-Agent"));
        return ApiResponse.ok("店铺密码已重置，现有会话保持有效", null);
    }

    @GetMapping("/sessions")
    public ApiResponse<List<WorkspaceAccountService.SessionView>> sessions() {
        return ApiResponse.ok(accounts.sessions());
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public ApiResponse<Void> revokeSession(@PathVariable long sessionId) {
        accounts.revokeSession(sessionId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/audit")
    public ApiResponse<List<WorkspaceAccountService.AuditView>> audit() {
        return ApiResponse.ok(accounts.auditLog());
    }

    @GetMapping("/unassigned")
    public ApiResponse<List<WorkspaceOwnershipService.UnassignedView>> unassigned() {
        return ApiResponse.ok(ownership.currentPending());
    }

    @GetMapping("/sync-settings")
    public ApiResponse<List<WorkspaceAccountService.SyncSettingView>> syncSettings() {
        return ApiResponse.ok(accounts.syncSettings());
    }

    @PatchMapping("/sync-settings/{category}")
    public ApiResponse<Void> setSyncEnabled(
            @PathVariable String category,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest servletRequest
    ) {
        accounts.setSyncEnabled(category, Boolean.TRUE.equals(body.get("enabled")),
                WorkspaceAuthController.clientIp(servletRequest), servletRequest.getHeader("User-Agent"));
        return ApiResponse.ok(null);
    }
}
