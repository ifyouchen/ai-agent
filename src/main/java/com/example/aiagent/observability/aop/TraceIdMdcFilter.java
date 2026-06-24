package com.example.aiagent.observability.aop;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceId 注入过滤器
 *
 * 每个 HTTP 请求进来时，从请求头读取或生成 traceId，
 * 写入 MDC，使每行日志都自动携带 traceId，便于链路追踪。
 *
 * 日志格式配置（application.yml）：
 *   logging.pattern.console: "%d [%thread] %-5level [%X{traceId},%X{userId}] %logger - %msg%n"
 */
@Slf4j
@Component
@Order(1)  // 最高优先级，第一个执行
public class TraceIdMdcFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String USER_ID_HEADER  = "X-User-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq  = (HttpServletRequest)  request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 优先使用上游传入的 TraceId，没有则自己生成
        String traceId = httpReq.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String userId = httpReq.getHeader(USER_ID_HEADER);

        // 写入 MDC，所有日志自动携带
        MDC.put("traceId",  traceId);
        MDC.put("userId",   userId != null ? userId : "anonymous");

        // 响应头回写，方便前端和下游服务追踪
        httpResp.setHeader(TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清理，避免线程池复用时 MDC 污染
            MDC.clear();
        }
    }
}
