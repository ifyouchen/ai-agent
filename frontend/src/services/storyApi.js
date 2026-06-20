import { http } from './http.js';

export const storyApi = {
  async listProjects(params = {}) {
    const { data } = await http.get('/api/v1/story/projects', { params });
    return data;
  },
  async createProject(payload) {
    const { data } = await http.post('/api/v1/story/projects', payload);
    return data;
  },
  async getProject(id) {
    const { data } = await http.get(`/api/v1/story/projects/${id}`);
    return data;
  },
  async createChapter(projectId, payload) {
    const { data } = await http.post(`/api/v1/story/projects/${projectId}/chapters`, payload);
    return data;
  },
  async updateChapter(chapterId, payload) {
    const { data } = await http.put(`/api/v1/story/chapters/${chapterId}`, payload);
    return data;
  },
  async listChapterVersions(chapterId) {
    const { data } = await http.get(`/api/v1/story/chapters/${chapterId}/versions`);
    return data;
  },
  async restoreChapter(chapterId, payload) {
    const { data } = await http.post(`/api/v1/story/chapters/${chapterId}/restore`, payload || {});
    return data;
  },
  async generate(projectId, payload) {
    const { data } = await http.post(`/api/v1/story/projects/${projectId}/generate`, payload);
    return data;
  },
  async importText(payload) {
    const { data } = await http.post('/api/v1/story/import/text', payload);
    return data;
  },
  async previewImportText(payload) {
    const { data } = await http.post('/api/v1/story/import/text/preview', payload);
    return data;
  },
  async importFile(file, title) {
    const formData = new FormData();
    formData.append('file', file);
    if (title) formData.append('title', title);
    const { data } = await http.post('/api/v1/story/import/file', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return data;
  },
  async previewImportFile(file, title) {
    const formData = new FormData();
    formData.append('file', file);
    if (title) formData.append('title', title);
    const { data } = await http.post('/api/v1/story/import/file/preview', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return data;
  },
  async createRewrite(payload) {
    const { data } = await http.post('/api/v1/story/rewrite', payload);
    return data;
  },
  async getRewrite(taskId) {
    const { data } = await http.get(`/api/v1/story/rewrite/${taskId}`);
    return data;
  },
  async acceptRewrite(taskId, payload) {
    const { data } = await http.post(`/api/v1/story/rewrite/${taskId}/accept`, payload || {});
    return data;
  },
  async retryRewrite(taskId, payload) {
    const { data } = await http.post(`/api/v1/story/rewrite/${taskId}/retry`, payload || {});
    return data;
  },
  async convertToScript(payload) {
    const { data } = await http.post('/api/v1/story/script/convert', payload);
    return data;
  },
  async getTask(taskId) {
    const { data } = await http.get(`/api/v1/story/tasks/${taskId}`);
    return data;
  },
  async cancelTask(taskId) {
    const { data } = await http.post(`/api/v1/story/tasks/${taskId}/cancel`);
    return data;
  },
  async retryTask(taskId) {
    const { data } = await http.post(`/api/v1/story/tasks/${taskId}/retry`);
    return data;
  },
  async getScriptDraft(draftId) {
    const { data } = await http.get(`/api/v1/story/script/drafts/${draftId}`);
    return data;
  },
  async improveEpisode(episodeId, payload) {
    const { data } = await http.post(`/api/v1/story/script/episodes/${episodeId}/ai`, payload || {});
    return data;
  },  async createScene(episodeId, payload) {
    const { data } = await http.post(`/api/v1/story/script/episodes/${episodeId}/scenes`, payload || {});
    return data;
  },
  async updateScene(sceneId, payload) {
    const { data } = await http.put(`/api/v1/story/script/scenes/${sceneId}`, payload);
    return data;
  },
  async deleteScene(sceneId) {
    const { data } = await http.delete(`/api/v1/story/script/scenes/${sceneId}`);
    return data;
  },
  async moveScene(sceneId, payload) {
    const { data } = await http.post(`/api/v1/story/script/scenes/${sceneId}/move`, payload || {});
    return data;
  },
  async improveScene(sceneId, payload) {
    const { data } = await http.post(`/api/v1/story/script/scenes/${sceneId}/ai`, payload || {});
    return data;
  },
  async checkQuality(draftId, payload) {
    const { data } = await http.post(`/api/v1/story/script/drafts/${draftId}/quality-check`, payload || {});
    return data;
  },
  async exportDraft(draftId, payload) {
    const { data } = await http.post(`/api/v1/story/script/drafts/${draftId}/export`, payload || {});
    return data;
  },
  async exportDraftFile(draftId, payload) {
    const response = await http.post(`/api/v1/story/script/drafts/${draftId}/export/file`, payload || {}, {
      responseType: 'blob',
    });
    return response;
  },
};
