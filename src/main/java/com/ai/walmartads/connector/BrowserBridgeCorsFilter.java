package com.ai.walmartads.connector;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

/** Restricts the extension-facing API to a local browser extension talking to loopback. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BrowserBridgeCorsFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/browser-bridge/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        if (!isLoopback(request) || origin == null || !origin.matches("chrome-extension://[a-p]{32}")) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Bridge-Key, X-Extension-Id");
        response.setHeader("Access-Control-Max-Age", "600");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
