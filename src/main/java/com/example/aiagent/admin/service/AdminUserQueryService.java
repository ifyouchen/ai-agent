package com.example.aiagent.admin.service;

import com.example.aiagent.observability.service.TokenUsageService;
import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.OrgMemberMapper;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

    private final SysUserMapper sysUserMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final TokenUsageService tokenUsageService;

    public Map<String, Object> listUsers(int page, int size, String keyword) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        String kw = normalize(keyword);
        List<Map<String, Object>> items = sysUserMapper
                .findByKeyword(kw, safePage * safeSize, safeSize)
                .stream()
                .map(this::userListItem)
                .toList();
        long total = sysUserMapper.countByKeyword(kw);
        return AdminPageResult.of(items, total, safePage, safeSize);
    }

    public Map<String, Object> userDetail(String userId) {
        SysUser user = sysUserMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在：" + userId));
        Map<String, Object> detail = userListItem(user);
        detail.put("email", user.getEmail());
        detail.put("createdAt", user.getCreatedAt());
        detail.put("updatedAt", user.getUpdatedAt());
        detail.put("orgCount", orgMemberMapper.countByUserId(userId));
        detail.put("todayCostUsd", tokenUsageService.getUserTodayCost(userId));
        detail.put("usage7d", tokenUsageService.getUserUsageSummary(userId, 7));
        return detail;
    }

    private Map<String, Object> userListItem(SysUser user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", user.getUserId());
        item.put("username", user.getUsername());
        item.put("nickname", user.getNickname());
        item.put("roles", user.getRoleList());
        item.put("enabled", user.getEnabled());
        item.put("defaultOrgId", user.getDefaultOrgId());
        item.put("createdAt", user.getCreatedAt());
        return item;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
