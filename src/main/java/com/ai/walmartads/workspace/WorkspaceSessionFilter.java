package com.ai.walmartads.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WorkspaceSessionFilter extends OncePerRequestFilter {

    private final WorkspaceAccountService accounts;
    private final ObjectMapper objectMapper;

    public WorkspaceSessionFilter(WorkspaceAccountService accounts, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Optional<WorkspacePrincipal> resolved = accounts.resolveSession(WorkspaceAuthController.sessionToken(request));
        WorkspacePrincipal principal = resolved.orElse(null);
        try {
            if (principal != null) WorkspaceContext.set(principal);
            boolean setupRequired = accounts.setupRequired();
            if (setupRequired && (path.equals("/") || path.equals("/login.html"))) {
                response.sendRedirect("/setup.html");
                return;
            }
            if (!setupRequired && (path.equals("/setup.html") || path.equals("/setup.js"))) {
                response.sendError(404);
                return;
            }
            if (setupRequired && isLocalSetupPath(path) && !isLoopback(request)) {
                response.sendError(403);
                return;
            }
            if (isPinksirResetPath(path) && !isLoopback(request)) {
                response.sendError(403);
                return;
            }
            if (isPublic(path)) {
                chain.doFilter(request, response);
                return;
            }
            if (principal == null) {
                if (path.startsWith("/api/")) {
                    jsonError(response, 401, "请先登录");
                } else if (path.equals("/") || path.endsWith(".html")) {
                    response.sendRedirect("/login.html");
                } else {
                    chain.doFilter(request, response);
                }
                return;
            }
            if (!principal.ready() && (path.equals("/") || path.equals("/index.html"))) {
                response.sendRedirect("/workspace.html");
                return;
            }
            if (!principal.ready() && path.startsWith("/api/")
                    && !path.startsWith("/api/workspace/") && !path.startsWith("/api/auth/")) {
                jsonError(response, 423, "当前账号尚未完成店铺与领星 MCP 绑定");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private boolean isPublic(String path) {
        if (path.startsWith("/api/browser-bridge/")) return true;
        if (path.equals("/login.html") || path.equals("/auth.css") || path.equals("/auth.js")
                || path.equals("/pinksir-reset.html") || path.equals("/pinksir-reset.js")
                || path.startsWith("/favicon") || path.startsWith("/error")) return true;
        if (path.equals("/api/auth/status") || path.equals("/api/auth/login")
                || path.equals("/api/auth/register/discover") || path.equals("/api/auth/register")
                || path.equals("/api/auth/pinksir-reset")) return true;
        if ((path.equals("/setup.html") || path.equals("/setup.js")) && accounts.setupRequired()) return true;
        if ((path.equals("/api/auth/setup") || path.equals("/api/auth/discover") || path.equals("/api/auth/bootstrap"))
                && accounts.setupRequired()) return true;
        return path.equals("/actuator/health") || path.equals("/api/health");
    }

    private boolean isPinksirResetPath(String path) {
        return path.equals("/pinksir-reset.html") || path.equals("/pinksir-reset.js")
                || path.equals("/api/auth/pinksir-reset");
    }

    private boolean isLocalSetupPath(String path) {
        return path.equals("/setup.html") || path.equals("/setup.js")
                || path.equals("/api/auth/setup") || path.equals("/api/auth/discover")
                || path.equals("/api/auth/bootstrap");
    }

    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private void jsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("success", false, "message", message, "data", Map.of()));
    }
}
