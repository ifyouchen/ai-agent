package com.example.aiagent.exception;

import com.example.aiagent.billing.exception.BillingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e, HttpServletRequest request,
                                                               HttpServletResponse response)
            throws AccessDeniedException, AuthenticationException, IOException {

        if (e instanceof AccessDeniedException ade) {
            throw ade;
        }
        if (e instanceof AuthenticationException ae) {
            throw ae;
        }

        if (isSseRequest(request)) {
            log.error("SSE 请求未处理的异常 uri={} userId={} msg={}", request.getRequestURI(), MDC.get("userId"), e.getMessage(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"服务内部错误\",\"message\":\"" +
                        (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "未知错误") + "\"}");
            }
            return null;
        }

        log.error("未处理的异常 uri={} userId={} msg={}", request.getRequestURI(), MDC.get("userId"), e.getMessage(), e);
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
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        log.warn("访问被拒绝 uri={} userId={} msg={}", request.getRequestURI(), MDC.get("userId"), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "权限不足", "message", "您没有权限访问该资源"));
    }

    /**
     * 401 Unauthorized — Token 缺失或过期（Controller 层抛出时兜底）。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationException e, HttpServletRequest request) {
        log.warn("认证失败 uri={} userId={} msg={}", request.getRequestURI(), MDC.get("userId"), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "未认证", "message", "请先登录或检查 Token 是否有效"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("参数错误 uri={} userId={} msg={}", request.getRequestURI(), MDC.get("userId"), e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "参数错误", "message", e.getMessage()));
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handleBilling(BillingException e, HttpServletRequest request) {
        log.warn("计费异常 uri={} userId={} status={} msg={}",
                request.getRequestURI(), MDC.get("userId"), e.status().value(), e.getMessage());
        return ResponseEntity.status(e.status())
                .body(Map.of("error", "计费异常", "message", e.getMessage()));
    }
}
