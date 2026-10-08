package com.ai.walmartads.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常映射。约定：可归因于调用方的问题一律返回 4xx，只有真正的服务端故障才返回 500。
 *
 * <p>★ 框架自己会抛的 4xx 异常必须显式登记 handler —— 否则它们会落到
 * {@link #handleUnexpected(Exception)} 被统一报成 500，把「路径打错 / 枚举值非法」
 * 这类调用方错误伪装成服务端故障，排查时严重误导（历史上已踩过两次）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 路由或静态资源不存在。Spring MVC 6.1+ 抛 NoResourceFoundException。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException exception) {
        // 路径打错属常态噪音，降级到 debug，不再污染 error 日志。
        log.debug("Resource not found: {}", exception.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("接口或资源不存在：" + exception.getResourcePath()));
    }

    /** 查询参数类型或取值非法（典型：枚举参数传了不存在的值，如 ?source=UNKNOWN）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "参数 " + exception.getName() + " 取值非法：" + exception.getValue()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("处理失败：" + exception.getMessage()));
    }
}
