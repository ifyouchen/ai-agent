package com.example.aiagent.kb.service;

import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the tenant-scoped RAG context used by chat endpoints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRagContextService {

    private final OrganizationService organizationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KbMemberService kbMemberService;

    public HybridRagContentRetriever.RetrievalContext resolve(String userId, String orgId, Long kbId) {
        if (kbId == null) {
            return null;
        }

        String tenantId = organizationService.resolveOrgId(userId, orgId);
        log.info("解析 RAG 上下文 userId={} orgId={} kbId={} tenantId={}", userId, orgId, kbId, tenantId);

        // Strictly bind the selected knowledge base to the current organization.
        knowledgeBaseService.getKnowledgeBase(tenantId, kbId);

        String role = kbMemberService.checkAccess(kbId, userId, tenantId);
        if (role == null) {
            log.warn("知识库权限检查未通过 userId={} kbId={} tenantId={}", userId, kbId, tenantId);
            throw new IllegalArgumentException("您没有访问该知识库的权限");
        }

        return new HybridRagContentRetriever.RetrievalContext(tenantId, kbId);
    }
}
