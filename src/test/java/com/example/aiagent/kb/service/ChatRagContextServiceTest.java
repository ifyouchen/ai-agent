package com.example.aiagent.kb.service;

import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.rag.retrieval.HybridRagContentRetriever;
import com.example.aiagent.security.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ChatRagContextService - 聊天 RAG 上下文")
@ExtendWith(MockitoExtension.class)
class ChatRagContextServiceTest {

    @Mock private OrganizationService organizationService;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private KbMemberService kbMemberService;

    private ChatRagContextService service;

    @BeforeEach
    void setUp() {
        service = new ChatRagContextService(organizationService, knowledgeBaseService, kbMemberService);
    }

    @Test
    @DisplayName("有 kbId 时应使用当前组织 ID 作为 tenantId")
    void shouldResolveOrgTenantForKbContext() {
        String userId = "user-1";
        String orgId = "ent_b53f07094aa9495e";
        Long kbId = 1L;

        when(organizationService.resolveOrgId(userId, orgId)).thenReturn(orgId);
        when(knowledgeBaseService.getKnowledgeBase(orgId, kbId)).thenReturn(
                KnowledgeBase.builder().id(kbId).tenantId(orgId).name("资质").build());
        when(kbMemberService.checkAccess(kbId, userId, orgId)).thenReturn("VIEWER");

        HybridRagContentRetriever.RetrievalContext context = service.resolve(userId, orgId, kbId);

        assertThat(context.tenantId()).isEqualTo(orgId);
        assertThat(context.kbId()).isEqualTo(kbId);
        verify(knowledgeBaseService).getKnowledgeBase(orgId, kbId);
    }

    @Test
    @DisplayName("没有 kbId 时不应启用知识库检索")
    void shouldNotResolveContextWithoutKbId() {
        HybridRagContentRetriever.RetrievalContext context =
                service.resolve("user-1", "org_user-1", null);

        assertThat(context).isNull();
        verifyNoInteractions(organizationService, knowledgeBaseService, kbMemberService);
    }

    @Test
    @DisplayName("无知识库访问权限时应拒绝")
    void shouldRejectInaccessibleKnowledgeBase() {
        String userId = "user-1";
        String orgId = "org_user-1";
        Long kbId = 2L;

        when(organizationService.resolveOrgId(userId, orgId)).thenReturn(orgId);
        when(knowledgeBaseService.getKnowledgeBase(orgId, kbId)).thenReturn(
                KnowledgeBase.builder().id(kbId).tenantId(orgId).name("网关").build());
        when(kbMemberService.checkAccess(kbId, userId, orgId)).thenReturn(null);

        assertThatThrownBy(() -> service.resolve(userId, orgId, kbId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有访问");
    }
}
