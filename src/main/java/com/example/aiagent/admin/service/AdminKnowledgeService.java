package com.example.aiagent.admin.service;

import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminKnowledgeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;

    public Map<String, Object> listKnowledgeBases(String keyword, String tenantId,
                                                  Integer status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String kw = normalize(keyword);
        String orgId = normalize(tenantId);
        return AdminPageResult.of(
                knowledgeBaseMapper.findAdminPage(kw, orgId, status, safePage * safeSize, safeSize),
                knowledgeBaseMapper.countAdminPage(kw, orgId, status),
                safePage,
                safeSize
        );
    }

    public Map<String, Object> listDocuments(String keyword, String parseStatus,
                                             Long kbId, String tenantId,
                                             int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String kw = normalize(keyword);
        String status = normalize(parseStatus);
        String orgId = normalize(tenantId);
        return AdminPageResult.of(
                documentMapper.findAdminPage(kw, status, kbId, orgId, safePage * safeSize, safeSize),
                documentMapper.countAdminPage(kw, status, kbId, orgId),
                safePage,
                safeSize
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
