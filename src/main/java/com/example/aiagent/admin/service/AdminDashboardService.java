package com.example.aiagent.admin.service;

import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.observability.service.TokenUsageService;
import com.example.aiagent.security.mapper.OrganizationMapper;
import com.example.aiagent.security.mapper.SysUserMapper;
import com.example.aiagent.story.mapper.GenerationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final SysUserMapper sysUserMapper;
    private final OrganizationMapper organizationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final GenerationTaskMapper generationTaskMapper;
    private final TokenUsageService tokenUsageService;

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todayCostUsd", tokenUsageService.getTodayTotalCost());
        result.put("errorRate", tokenUsageService.getRecentErrorRate(5));
        result.put("userCount", sysUserMapper.countAll());
        result.put("organizationCount", organizationMapper.countAll());
        result.put("knowledgeBaseCount", knowledgeBaseMapper.countAll());
        result.put("documentCount", documentMapper.countAll());
        result.put("failedDocumentCount", documentMapper.countByParseStatus("FAILED"));
        result.put("runningTaskCount", runningTaskCount());
        result.put("failedTaskCount", generationTaskMapper.countByStatus("FAILED"));
        result.put("topUsers", tokenUsageService.getUserCostReport(7));
        result.put("recentFailedDocuments", documentMapper.findRecentFailed(5));
        result.put("recentFailedTasks", generationTaskMapper.findAdminPage("FAILED", 0, 5));
        return result;
    }

    private long runningTaskCount() {
        return generationTaskMapper.countByStatus("PENDING")
                + generationTaskMapper.countByStatus("RUNNING")
                + generationTaskMapper.countByStatus("PROCESSING");
    }
}
