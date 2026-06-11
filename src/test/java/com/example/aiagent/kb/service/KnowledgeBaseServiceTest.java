package com.example.aiagent.kb.service;

import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.ChunkMapper;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.mapper.KnowledgeBaseMapper;
import com.example.aiagent.kb.mapper.RetrievalLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 单元测试（Mock Mapper）
 *
 * 覆盖：多租户隔离（跨租户访问应抛异常）、重名知识库拦截、级联删除
 */
@DisplayName("KnowledgeBaseService - 知识库管理")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock private KnowledgeBaseMapper   kbMapper;
    @Mock private DocumentMapper        documentMapper;
    @Mock private ChunkMapper           chunkMapper;
    @Mock private RetrievalLogMapper    retrievalLogMapper;

    private KnowledgeBaseService kbService;

    @BeforeEach
    void setUp() {
        kbService = new KnowledgeBaseService(kbMapper, documentMapper, chunkMapper, retrievalLogMapper);
    }

    // ── 创建知识库 ─────────────────────────────────────────

    @Test
    @DisplayName("正常创建知识库应成功")
    void shouldCreateKnowledgeBase() {
        when(kbMapper.findByTenantIdAndName("tenant-A", "产品手册"))
                .thenReturn(Optional.empty());
        doAnswer(inv -> {
            KnowledgeBase kb = inv.getArgument(0);
            kb.setId(1L);
            return null;
        }).when(kbMapper).insert(any(KnowledgeBase.class));

        KnowledgeBase result = kbService.createKnowledgeBase("tenant-A", "产品手册", "描述");

        assertThat(result.getName()).isEqualTo("产品手册");
        assertThat(result.getTenantId()).isEqualTo("tenant-A");
        verify(kbMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("同租户重名知识库应抛出 IllegalArgumentException")
    void shouldThrowWhenDuplicateNameInSameTenant() {
        KnowledgeBase existing = KnowledgeBase.builder()
                .id(1L).tenantId("tenant-A").name("产品手册").build();
        when(kbMapper.findByTenantIdAndName("tenant-A", "产品手册"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> kbService.createKnowledgeBase("tenant-A", "产品手册", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");

        verify(kbMapper, never()).insert(any());
    }

    // ── 多租户隔离 ─────────────────────────────────────────

    @Test
    @DisplayName("获取其他租户的知识库应抛出 IllegalArgumentException（多租户隔离）")
    void shouldThrowWhenAccessingOtherTenantKb() {
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(1L).tenantId("tenant-B").name("敏感数据").build();
        when(kbMapper.findById(1L)).thenReturn(Optional.of(kb));

        // tenant-A 尝试访问 tenant-B 的知识库
        assertThatThrownBy(() -> kbService.getKnowledgeBase("tenant-A", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于租户");
    }

    @Test
    @DisplayName("知识库不存在时应抛出 IllegalArgumentException")
    void shouldThrowWhenKbNotFound() {
        when(kbMapper.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kbService.getKnowledgeBase("tenant-A", 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    // ── 删除知识库 ─────────────────────────────────────────

    @Test
    @DisplayName("删除知识库应级联删除文档和切片")
    void shouldCascadeDeleteDocumentsAndChunks() {
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(1L).tenantId("tenant-A").name("要删除的库").build();
        when(kbMapper.findById(1L)).thenReturn(Optional.of(kb));

        com.example.aiagent.kb.entity.Document doc1 =
                com.example.aiagent.kb.entity.Document.builder().id(10L).kbId(1L).build();
        com.example.aiagent.kb.entity.Document doc2 =
                com.example.aiagent.kb.entity.Document.builder().id(11L).kbId(1L).build();
        when(documentMapper.findByKbId(1L)).thenReturn(List.of(doc1, doc2));

        kbService.deleteKnowledgeBase("tenant-A", 1L);

        // 切片应被删除（2篇文档 → 2次调用）
        verify(chunkMapper, times(2)).deleteByDocId(anyLong());
        // 文档应被删除（2次）
        verify(documentMapper, times(2)).deleteById(anyLong());
        // 知识库应被删除（1次）
        verify(kbMapper).deleteById(1L);
    }

    @Test
    @DisplayName("列出知识库应只返回当前租户的数据")
    void shouldListOnlyCurrentTenantKbs() {
        List<KnowledgeBase> kbs = List.of(
                KnowledgeBase.builder().id(1L).tenantId("tenant-A").name("KB1").build(),
                KnowledgeBase.builder().id(2L).tenantId("tenant-A").name("KB2").build()
        );
        when(kbMapper.findByTenantId("tenant-A")).thenReturn(kbs);

        List<KnowledgeBase> result = kbService.listKnowledgeBases("tenant-A");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(kb -> "tenant-A".equals(kb.getTenantId()));
    }
}

