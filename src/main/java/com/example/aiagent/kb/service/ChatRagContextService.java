package com.example.aiagent.kb.service;

import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves the tenant-scoped RAG context used by chat endpoints.
 */
@Service
@RequiredArgsConstructor
public class ChatRagContextService {

    private final OrganizationService organizationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KbMemberService kbMemberService;

    public HybridRagContentRetriever.RetrievalContext resolve(String userId, String orgId, Long kbId) {
        String tenantId = organizationService.resolveOrgId(userId, orgId);

        if (kbId == null) {
            return new HybridRagContentRetriever.RetrievalContext(tenantId, null);
        }

        // Strictly bind the selected knowledge base to the current organization.
        knowledgeBaseService.getKnowledgeBase(tenantId, kbId);

        String role = kbMemberService.checkAccess(kbId, userId, tenantId);
        if (role == null) {
            throw new IllegalArgumentException("您没有访问该知识库的权限");
        }

        return new HybridRagContentRetriever.RetrievalContext(tenantId, kbId);
    }
}
