package com.example.aiagent.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 通用兜底处理器。
     *
     * <p><b>注意：</b>Spring Security 的 {@link AccessDeniedException} 和
     * {@link AuthenticationException} 必须重新抛出，
     * 让 {@code ExceptionTranslationFilter} 完成正确的 403/401 响应。
     * 如果在这里直接返回 500，Response 会被提前 commit，
     * 导致 "Unable to handle the Spring Security Exception because the response is already committed"。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e)
            throws AccessDeniedException, AuthenticationException {

        // 必须将 Spring Security 异常重新抛出，不能在此处消费
        if (e instanceof AccessDeniedException ade) {
            throw ade;
        }
        if (e instanceof AuthenticationException ae) {
            throw ae;
        }

        log.error("未处理的异常: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(Map.of(
                        "error", "服务内部错误",
                        "message", e.getMessage() != null ? e.getMessage() : "未知错误"
                ));
    }

    /**
     * 403 Forbidden — 已认证但权限不足（Controller 层抛出时兜底）。
     *
     * <p>过滤器阶段的 403 仍由 Spring Security {@code ExceptionTranslationFilter} 处理，
     * 此处只捕获 Controller / Service 层主动抛出的场景。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        log.warn("访问被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "权限不足", "message", "您没有权限访问该资源"));
    }

    /**
     * 401 Unauthorized — Token 缺失或过期（Controller 层抛出时兜底）。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "未认证", "message", "请先登录或检查 Token 是否有效"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "参数错误", "message", e.getMessage()));
    }
}
