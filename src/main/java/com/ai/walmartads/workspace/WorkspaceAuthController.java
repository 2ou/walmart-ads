package com.ai.walmartads.workspace;

import com.ai.walmartads.shared.api.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class WorkspaceAuthController {

    private final WorkspaceAccountService accounts;
    private final WorkspaceInitializationService initialization;

    public WorkspaceAuthController(WorkspaceAccountService accounts, WorkspaceInitializationService initialization) {
        this.accounts = accounts;
        this.initialization = initialization;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        boolean setupRequired = accounts.setupRequired();
        WorkspacePrincipal principal = WorkspaceContext.current().orElse(null);
        return ApiResponse.ok(Map.of(
                "setupRequired", setupRequired,
                "authenticated", principal != null,
                "account", principal == null ? Map.of() : principal));
    }

    @GetMapping("/bootstrap")
    public ApiResponse<WorkspaceAccountService.LegacyBootstrap> bootstrap() {
        if (!accounts.setupRequired()) {
            throw new IllegalStateException("首次初始化已经完成");
        }
        return ApiResponse.ok(accounts.legacyBootstrap());
    }

    @PostMapping("/discover")
    public ApiResponse<?> discover(@RequestBody(required = false) WorkspaceAccountService.DiscoverRequest request) {
        return ApiResponse.ok(accounts.discover(request));
    }

    @PostMapping("/setup")
    public ApiResponse<WorkspaceAccountService.SetupResult> setup(
            @RequestBody WorkspaceAccountService.SetupRequest request,
            HttpServletRequest servletRequest
    ) {
        requireLoopback(servletRequest);
        return ApiResponse.ok("初始化完成，请登录", accounts.setupPrimary(
                request, clientIp(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(
            @RequestBody WorkspaceAccountService.LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response
    ) {
        if (accounts.setupRequired()) {
            throw new IllegalStateException("请先在本机完成首次初始化");
        }
        WorkspaceAccountService.LoginResult login = accounts.login(
                request.username(), request.password(), clientIp(servletRequest), servletRequest.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(login.token(), login.maxAgeSeconds()).toString());
        return ApiResponse.ok(Map.of("account", login.principal(),
                "redirect", login.principal().ready() ? "/" : "/workspace.html"));
    }

    @PostMapping("/register/discover")
    public ApiResponse<List<WorkspaceAccountService.StoreOption>> discoverRegistrationStores(
            @RequestBody WorkspaceAccountService.RegisterDiscoveryRequest request
    ) {
        return ApiResponse.ok(accounts.discoverRegistrationStores(request));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(
            @RequestBody WorkspaceAccountService.RegisterAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response
    ) {
        WorkspaceAccountService.SetupResult created = accounts.registerAccount(
                request, clientIp(servletRequest), servletRequest.getHeader("User-Agent"));
        WorkspaceAccountService.LoginResult login = accounts.login(
                created.account().username(), request.password(), clientIp(servletRequest),
                servletRequest.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(login.token(), login.maxAgeSeconds()).toString());
        initialization.startIfNeeded(login.principal());
        return ApiResponse.ok("注册成功", Map.of(
                "account", login.principal(),
                "redirect", login.principal().ready() ? "/" : "/workspace.html"));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        WorkspaceContext.current().ifPresent(principal -> accounts.logout(
                principal, clientIp(request), request.getHeader("User-Agent")));
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie().toString());
        return ApiResponse.ok("已退出登录", null);
    }

    @PostMapping("/pinksir-reset")
    public ApiResponse<Void> resetPinksir(
            @RequestBody WorkspaceAccountService.PasswordResetRequest request,
            HttpServletRequest servletRequest
    ) {
        requireLoopback(servletRequest);
        accounts.emergencyResetPinksir(request, clientIp(servletRequest), servletRequest.getHeader("User-Agent"));
        return ApiResponse.ok("PINKSIR 密码已重置，现有会话保持有效", null);
    }

    private static ResponseCookie sessionCookie(String token, int maxAgeSeconds) {
        return ResponseCookie.from(WorkspaceAccountService.SESSION_COOKIE, token)
                .httpOnly(true).sameSite("Strict").path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds)).build();
    }

    private static ResponseCookie expiredCookie() {
        return ResponseCookie.from(WorkspaceAccountService.SESSION_COOKIE, "")
                .httpOnly(true).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    }

    static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (WorkspaceAccountService.SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    static void requireLoopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new IllegalStateException("该操作仅允许在服务器本机完成");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("无法确认本机访问来源");
        }
    }
}
