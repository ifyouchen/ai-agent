package com.example.aiagent.story;

import com.example.aiagent.story.entity.GenerationTask;
import com.example.aiagent.story.entity.ScriptDraft;
import com.example.aiagent.story.entity.StoryChapter;
import com.example.aiagent.story.entity.StoryProject;
import com.example.aiagent.story.mapper.GenerationTaskMapper;
import com.example.aiagent.story.mapper.RewriteTaskMapper;
import com.example.aiagent.story.mapper.ScriptDraftMapper;
import com.example.aiagent.story.mapper.ScriptEpisodeMapper;
import com.example.aiagent.story.mapper.ScriptSceneMapper;
import com.example.aiagent.story.mapper.StoryChapterMapper;
import com.example.aiagent.story.mapper.StoryChapterVersionMapper;
import com.example.aiagent.story.mapper.StoryProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoryWorkspaceService - 创作工作台概览")
class StoryWorkspaceServiceTest {

    @Mock private StoryProjectMapper projectMapper;
    @Mock private StoryChapterMapper chapterMapper;
    @Mock private StoryChapterVersionMapper chapterVersionMapper;
    @Mock private RewriteTaskMapper rewriteTaskMapper;
    @Mock private ScriptDraftMapper draftMapper;
    @Mock private ScriptEpisodeMapper episodeMapper;
    @Mock private ScriptSceneMapper sceneMapper;
    @Mock private GenerationTaskMapper generationTaskMapper;
    @Mock private StoryAiService storyAiService;
    @Mock private StoryExportService storyExportService;
    @Mock private StoryImportService storyImportService;

    @InjectMocks private StoryWorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new StoryWorkspaceService(
                projectMapper,
                chapterMapper,
                chapterVersionMapper,
                rewriteTaskMapper,
                draftMapper,
                episodeMapper,
                sceneMapper,
                generationTaskMapper,
                new ObjectMapper(),
                storyAiService,
                storyExportService,
                storyImportService
        );
        lenient().when(chapterMapper.findByProjectId(anyLong())).thenReturn(List.of());
        lenient().when(draftMapper.findByProjectId(anyLong())).thenReturn(List.of());
        lenient().when(generationTaskMapper.findRecent(anyInt())).thenReturn(List.of());
        lenient().when(generationTaskMapper.findRecentByProject(anyLong(), anyInt())).thenReturn(List.of());
        lenient().when(rewriteTaskMapper.findRecent(anyInt())).thenReturn(List.of());
        lenient().when(rewriteTaskMapper.findRecentByProject(anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("无作品时返回空概览")
    void shouldReturnEmptyWorkbenchSummary() {
        when(projectMapper.findByTenantId("default", null)).thenReturn(List.of());

        Map<String, Object> summary = service.workbenchSummary();

        Map<String, Object> overview = castMap(summary.get("overview"));
        assertThat(overview).containsEntry("projectCount", 0);
        assertThat(overview).containsEntry("runningTaskCount", 0);
        assertThat(summary.get("activeTasks")).asList().isEmpty();
        assertThat(summary.get("recentProjects")).asList().isEmpty();
    }

    @Test
    @DisplayName("写作项目给出继续写作下一步")
    void shouldSuggestWritingNextStep() {
        StoryProject project = project(1L, "长篇测试", "long_novel", "writing", 2, 0);
        when(projectMapper.findByTenantId("default", null)).thenReturn(List.of(project));
        when(chapterMapper.findByProjectId(1L)).thenReturn(List.of(
                chapter(11L, 1, "第1章", 1200),
                chapter(12L, 2, "第2章", 900)
        ));

        Map<String, Object> projectSummary = firstRecentProject(service.workbenchSummary());

        assertThat(projectSummary).containsEntry("workflowStage", "写作中");
        assertThat(projectSummary).containsEntry("nextAction", "继续写作");
        assertThat(projectSummary).containsEntry("nextActionUrl", "/creation/projects/1/editor");
        assertThat(castMap(projectSummary.get("latestChapter"))).containsEntry("title", "第2章");
    }

    @Test
    @DisplayName("运行中任务进入 activeTasks 并优先作为项目下一步")
    void shouldSurfaceRunningTask() {
        StoryProject project = project(2L, "改编项目", "adaptation", "adapting", 1, 0);
        GenerationTask task = GenerationTask.builder()
                .id(31L)
                .taskType("script_convert")
                .projectId(2L)
                .status("running")
                .progress(35)
                .currentStep("正在生成改编方案和分集")
                .tokenUsage("{}")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(projectMapper.findByTenantId("default", null)).thenReturn(List.of(project));
        when(generationTaskMapper.findRecent(30)).thenReturn(List.of(task));
        when(generationTaskMapper.findRecentByProject(2L, 1)).thenReturn(List.of(task));

        Map<String, Object> summary = service.workbenchSummary();
        Map<String, Object> projectSummary = firstRecentProject(summary);
        Map<String, Object> activeTask = castMap(castList(summary.get("activeTasks")).get(0));

        assertThat(castMap(summary.get("overview"))).containsEntry("runningTaskCount", 1);
        assertThat(activeTask).containsEntry("kind", "generation").containsEntry("progress", 35);
        assertThat(projectSummary).containsEntry("workflowStage", "短剧生成中");
        assertThat(projectSummary).containsEntry("nextAction", "查看生成进度");
    }

    @Test
    @DisplayName("有质检分的短剧草稿进入导出闭环")
    void shouldSuggestExportWhenDraftHasQualityScore() {
        StoryProject project = project(3L, "短剧项目", "short_drama", "adapting", 3, 1);
        when(projectMapper.findByTenantId("default", null)).thenReturn(List.of(project));
        when(draftMapper.findByProjectId(3L)).thenReturn(List.of(ScriptDraft.builder()
                .id(41L)
                .projectId(3L)
                .title("短剧项目 - 分场稿")
                .episodeCount(3)
                .qualityScore(86)
                .status("draft")
                .adaptationPlan("{}")
                .qualityReport("{\"totalScore\":86}")
                .updatedAt(Instant.now())
                .build()));

        Map<String, Object> projectSummary = firstRecentProject(service.workbenchSummary());

        assertThat(projectSummary).containsEntry("workflowStage", "可导出");
        assertThat(projectSummary).containsEntry("exportReady", true);
        assertThat(projectSummary).containsEntry("nextActionUrl", "/creation/scripts/41/export");
    }

    private StoryProject project(Long id, String title, String type, String status, int chapters, int drafts) {
        return StoryProject.builder()
                .id(id)
                .tenantId("default")
                .title(title)
                .type(type)
                .status(status)
                .description("")
                .metadata("{}")
                .chapterCount(chapters)
                .scriptDraftCount(drafts)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private StoryChapter chapter(Long id, int chapterNo, String title, int wordCount) {
        return StoryChapter.builder()
                .id(id)
                .projectId(1L)
                .chapterNo(chapterNo)
                .title(title)
                .content("")
                .wordCount(wordCount)
                .versionNo(1)
                .status("draft")
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> firstRecentProject(Map<String, Object> summary) {
        return castMap(castList(summary.get("recentProjects")).get(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
