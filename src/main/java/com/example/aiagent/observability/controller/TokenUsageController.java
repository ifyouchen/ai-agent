package com.example.aiagent.observability.controller;

import com.example.aiagent.observability.service.TokenUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 用量与成本报表接口
 *
 * /api/v1/admin/token-usage/**  → 需要 ADMIN 角色（SecurityConfig 统一控制）
 * /api/v1/token-usage/my        → 普通用户查看自己的费用
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    /**
     * 查看自己今日费用
     * GET /api/v1/token-usage/my/today
     */
    @GetMapping("/api/v1/token-usage/my/today")
    public ResponseEntity<Map<String, Object>> myTodayCost(
            @AuthenticationPrincipal String userId) {

        BigDecimal cost = tokenUsageService.getUserTodayCost(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId",   userId);
        result.put("costUsd",  cost);
        result.put("currency", "USD");
        return ResponseEntity.ok(result);
    }

    /**
     * 查看自己近 N 天每日费用趋势（普通用户可用）
     * GET /api/v1/token-usage/my/daily?days=7
     */
    @GetMapping("/api/v1/token-usage/my/daily")
    public ResponseEntity<List<Map<String, Object>>> myDailyCost(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int days) {

        if (!isValidPersonalDays(days)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getUserDailyCostReport(userId, days));
    }

    /**
     * 查看自己近 N 天 Token 汇总（普通用户可用，最多 30 天）
     * GET /api/v1/token-usage/my/summary?days=7
     */
    @GetMapping("/api/v1/token-usage/my/summary")
    public ResponseEntity<Map<String, Object>> myUsageSummary(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int days) {

        if (!isValidPersonalDays(days)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getUserUsageSummary(userId, days));
    }

    /**
     * 查看自己近 N 天 Token 明细（普通用户可用，最多 30 天）
     * GET /api/v1/token-usage/my/details?days=7&page=1&size=10
     */
    @GetMapping("/api/v1/token-usage/my/details")
    public ResponseEntity<Map<String, Object>> myUsageDetails(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (!isValidPersonalDays(days) || page < 1 || size < 1 || size > 50) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getUserUsageDetailPage(userId, days, page, size));
    }

    // ── 管理员接口 ────────────────────────────────────────

    /**
     * 今日全局总费用
     * GET /api/v1/admin/token-usage/today
     */
    @GetMapping("/api/v1/admin/token-usage/today")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> todayTotalCost() {
        BigDecimal cost = tokenUsageService.getTodayTotalCost();
        return ResponseEntity.ok(Map.of("costUsd", cost, "currency", "USD"));
    }

    /**
     * 近 N 天按模型统计成本报表
     * GET /api/v1/admin/token-usage/report/model?days=7
     */
    @GetMapping("/api/v1/admin/token-usage/report/model")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> modelCostReport(
            @RequestParam(defaultValue = "7") int days) {

        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getModelCostReport(days));
    }

    /**
     * 近 N 天按用户统计 Top 消费用户
     * GET /api/v1/admin/token-usage/report/user?days=7
     */
    @GetMapping("/api/v1/admin/token-usage/report/user")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> userCostReport(
            @RequestParam(defaultValue = "7") int days) {

        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getUserCostReport(days));
    }

    /**
     * 近 N 天按天统计费用趋势（用于折线图）
     * GET /api/v1/admin/token-usage/report/daily?days=7
     */
    @GetMapping("/api/v1/admin/token-usage/report/daily")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> dailyCostReport(
            @RequestParam(defaultValue = "7") int days) {

        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(tokenUsageService.getDailyCostReport(days));
    }

    /**
     * 近 N 分钟错误率（运维监控用）
     * GET /api/v1/admin/token-usage/error-rate?minutes=5
     */
    @GetMapping("/api/v1/admin/token-usage/error-rate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> errorRate(
            @RequestParam(defaultValue = "5") int minutes) {

        double rate = tokenUsageService.getRecentErrorRate(minutes);
        return ResponseEntity.ok(Map.of(
                "minutes",   minutes,
                "errorRate", rate,
                "errorPct",  String.format("%.2f%%", rate * 100)
        ));
    }

    private boolean isValidPersonalDays(int days) {
        return days >= 1 && days <= 30;
    }
}

