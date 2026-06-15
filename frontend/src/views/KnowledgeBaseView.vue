<template>
  <div class="kb-view">
    <!-- 顶部：知识库选择器 -->
    <div class="kb-selector-header">
      <h3 class="kb-selector-title">
        知识库
        <svg v-if="kb.kbLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
        </svg>
        <span class="kb-org-badge" :title="'当前组织：' + org.currentOrgName">{{ org.currentOrgName }}</span>
      </h3>
      <button v-if="hasKnowledgeBases" class="kb-create-btn" type="button" @click="handleCreateKb">
        <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
          <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/>
          <path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        新建知识库
      </button>
    </div>

    <!-- 知识库卡片列表 -->
    <div class="kb-list">
      <!-- 骨架屏 -->
      <template v-if="kb.kbLoading && !kb.knowledgeBases.length">
        <div v-for="i in 3" :key="i" class="loading-item-skeleton" style="flex:1 1 220px; min-width:200px; max-width:280px;"></div>
      </template>

      <!-- 空状态 -->
      <div v-else-if="!kb.kbLoading && !hasKnowledgeBases" class="empty-state kb-list-empty">
        <div class="empty-state-icon">
          <svg viewBox="0 0 24 24" fill="none" width="32" height="32">
            <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </div>
        <div class="empty-state-text">暂无知识库</div>
        <div class="empty-state-hint">创建第一个知识库后即可上传文档并开始检索</div>
        <button class="kb-create-btn kb-empty-create-btn" type="button" @click="handleCreateKb">
          <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
            <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/>
            <path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          新建知识库
        </button>
      </div>

      <!-- 知识库卡片 -->
      <div
        v-for="item in kb.knowledgeBases"
        :key="item.id"
        class="kb-card"
        :class="{ active: item.id === kb.currentKbId }"
        @click="kb.selectKb(item.id, org.currentOrgId)"
      >
        <div class="kb-card-header">
          <div class="kb-card-icon">
            <svg viewBox="0 0 24 24" fill="none" width="18" height="18">
              <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="kb-card-actions">
            <template v-if="canManageKb">
              <button class="kb-card-action-btn" type="button" title="编辑知识库" @click.stop="handleEditKb(item)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <button class="kb-card-action-btn danger" type="button" title="删除知识库" @click.stop="handleDeleteKb(item)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 6V4h4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
            </template>
          </div>
        </div>
        <div class="kb-card-name">{{ item.name }}</div>
        <div class="kb-card-meta">
          <svg viewBox="0 0 24 24" fill="none" width="12" height="12">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <polyline points="14 2 14 8 20 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          {{ item.docCount || 0 }} 篇文档
        </div>
        <div v-if="item.description" class="kb-card-desc" :title="item.description">{{ item.description }}</div>
      </div>
    </div>

    <!-- 主内容区 -->
    <div v-if="kb.kbLoading || hasKnowledgeBases || kb.currentKbId" class="kb-main-area">
      <!-- 未选择知识库 -->
      <div v-if="!kb.currentKbId" class="empty-state">
        <div class="empty-state-icon">
          <svg viewBox="0 0 24 24" fill="none" width="32" height="32">
            <path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </div>
        <div class="empty-state-text">请选择一个知识库</div>
        <div class="empty-state-hint">点击上方知识库卡片开始管理文档</div>
      </div>

      <template v-else>
        <!-- 头部：Tab + 操作 -->
        <div class="kb-main-header">
          <div class="kb-tabs">
            <button class="kb-tab" :class="{ active: activeTab === 'docs' }" type="button" @click="activeTab = 'docs'">
              <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <polyline points="14 2 14 8 20 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              文档管理
              <svg v-if="kb.docsLoading && activeTab === 'docs'" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="12" height="12">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
            </button>
            <button class="kb-tab" :class="{ active: activeTab === 'query' }" type="button" @click="activeTab = 'query'">
              <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
                <path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              测试查询
            </button>
            <!-- Fix 1: 成员管理 Tab -->
            <button class="kb-tab" :class="{ active: activeTab === 'members' }" type="button" @click="switchToMembersTab">
              <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="2"/>
                <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              成员管理
              <span v-if="kb.kbMembers.length" class="kb-tab-badge">{{ kb.kbMembers.length }}</span>
            </button>
          </div>
          <div class="kb-header-actions">
            <button v-if="activeTab === 'docs'" class="kb-action-btn" type="button" title="刷新文档列表" @click="kb.loadDocs(org.currentOrgId)">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              刷新
            </button>
            <button class="kb-action-btn primary" type="button" @click="useKbInChat">
              <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              在对话中使用
            </button>
          </div>
        </div>

        <!-- Fix 13: KB 统计栏 -->
        <div v-if="kbStats" class="kb-stats-bar">
          <div class="kb-stat-item">
            <span class="kb-stat-num">{{ kbStats.docCount }}</span>
            <span class="kb-stat-label">篇文档</span>
          </div>
          <div class="kb-stat-divider"></div>
          <div class="kb-stat-item">
            <span class="kb-stat-num">{{ kbStats.chunkCount }}</span>
            <span class="kb-stat-label">个切片</span>
          </div>
          <div class="kb-stat-divider"></div>
          <div class="kb-stat-item">
            <span class="kb-stat-num">{{ kbStats.recentQueries }}</span>
            <span class="kb-stat-label">近 7 天查询</span>
          </div>
        </div>

        <!-- 文档管理 Tab -->
        <div v-if="activeTab === 'docs'">
          <!-- 上传区 -->
          <div
            class="kb-upload-area"
            :class="{ 'drag-over': dragOver }"
            @click="triggerUpload"
            @dragover.prevent="dragOver = true"
            @dragleave="dragOver = false"
            @drop.prevent="handleDrop"
          >
            <div class="kb-upload-icon">
              <svg viewBox="0 0 24 24" fill="none" width="28" height="28">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <polyline points="14 2 14 8 20 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <line x1="12" y1="18" x2="12" y2="12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <line x1="9" y1="15" x2="15" y2="15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </div>
            <div class="kb-upload-title">上传文档到当前知识库</div>
            <div class="kb-upload-desc">支持 PDF、Word、TXT、Markdown · 拖拽或点击上传 · 最大 50MB</div>
            <button class="upload-btn" type="button">选择文件</button>
            <input
              ref="fileInputEl"
              type="file"
              multiple
              accept=".pdf,.doc,.docx,.txt,.md"
              style="display:none"
              @change="handleFileChange"
            />
          </div>

          <!-- 上传队列 -->
          <div v-if="kb.uploadQueue.length > 0" class="upload-queue">
            <div class="upload-queue-header">
              <span>{{ queueSummary }}</span>
              <span v-if="queueFinished" class="upload-queue-done">
                <svg viewBox="0 0 24 24" fill="none" width="13" height="13">
                  <path d="m5 12 4 4L19 6" stroke="#00A96E" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                全部完成
              </span>
              <!-- Fix 6: 手动清除已完成的上传记录 -->
              <button v-if="hasCompletedUploads" class="kb-text-btn" type="button" @click="kb.clearCompletedUploads()">
                清除已完成
              </button>
            </div>
            <div v-for="task in kb.uploadQueue" :key="task.id" class="upload-task">
              <div class="upload-task-top">
                <span class="upload-task-name" :title="task.filename">{{ task.filename }}</span>
                <span class="upload-task-status" :class="`status-${task.status}`">
                  <template v-if="task.status === 'uploading'">{{ task.pct }}%</template>
                  <template v-else-if="task.status === 'processing'">解析中…</template>
                  <template v-else-if="task.status === 'done'">✓ 完成</template>
                  <template v-else-if="task.status === 'error'">✗ 失败</template>
                  <template v-else>等待</template>
                </span>
              </div>
              <div class="upload-task-bar">
                <div class="upload-task-fill" :class="`fill-${task.status}`" :style="{ width: task.barWidth + '%' }"></div>
              </div>
              <div v-if="task.status === 'uploading'" class="upload-task-meta">
                <span>{{ task.speedText }}</span>
                <span v-if="task.etaText">剩余 {{ task.etaText }}</span>
                <span>{{ task.loadedText }} / {{ task.totalText }}</span>
              </div>
              <div v-if="task.status === 'error'" class="upload-task-error">{{ task.error }}</div>
            </div>
          </div>

          <!-- Fix 5: 文档列表工具栏 -->
          <div class="kb-docs-toolbar">
            <span class="kb-docs-title">已导入文档</span>
            <div v-if="kb.docs.length > 1" class="kb-docs-filters">
              <select v-model="docStatusFilter" class="kb-filter-select">
                <option value="">全部状态</option>
                <option value="DONE">已完成</option>
                <option value="FAILED">解析失败</option>
                <option value="PROCESSING">处理中</option>
              </select>
              <select v-model="docSortBy" class="kb-filter-select">
                <option value="time_desc">最新上传</option>
                <option value="time_asc">最早上传</option>
                <option value="name_asc">名称 A→Z</option>
                <option value="size_desc">文件最大</option>
              </select>
            </div>
          </div>
          <div class="doc-list">
            <div v-if="!kb.docs.length" class="empty-state">
              <div class="empty-state-icon">
                <svg viewBox="0 0 24 24" fill="none" width="32" height="32">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                  <polyline points="14 2 14 8 20 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </div>
              <div class="empty-state-text">暂无文档</div>
              <div class="empty-state-hint">上传后 AI 可基于文档内容回答</div>
            </div>

            <div v-for="doc in filteredDocs" :key="doc.id" class="doc-item-wrapper">
              <div
                class="doc-item"
                :class="{ 'doc-item-expanded': expandedDocId === doc.id }"
                @click="toggleDocChunks(doc)"
              >
                <div class="doc-icon" :class="getFileIcon(doc.filename).cls" v-html="getFileIcon(doc.filename).icon"></div>
                <div class="doc-info">
                  <div class="doc-name">
                    {{ doc.filename }}
                    <span v-if="['PROCESSING','PENDING','PARSING','CHUNKING','EMBEDDING'].includes(doc.status)" class="doc-status-badge processing">
                      <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="10" height="10">
                        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="3" stroke-dasharray="14 50" stroke-linecap="round"/>
                      </svg>
                      {{ statusLabel(doc.status) }}
                      <!-- Fix 11: CHUNKING/EMBEDDING 阶段显示切片进度 -->
                      <span v-if="['CHUNKING','EMBEDDING'].includes(doc.status) && doc.chunks > 0" class="doc-progress-hint">
                        · 已切片 {{ doc.chunks }} 段
                      </span>
                    </span>
                    <span v-else-if="doc.status === 'FAILED'" class="doc-status-badge failed" :title="doc.parseError">解析失败</span>
                    <!-- Fix 3: 解析失败时展示重试按钮 -->
                    <button v-if="doc.status === 'FAILED'" class="doc-retry-btn" type="button" @click.stop="retryDoc(doc)">
                      重新解析
                    </button>
                  </div>
                  <div class="doc-meta">
                    {{ doc.chunks > 0 ? doc.chunks + ' 个切片' : '待切片' }}
                    {{ doc.size ? ` · ${formatFileSize(doc.size)}` : '' }}
                    · {{ doc.uploadedAt }}
                    <span v-if="doc.chunks > 0" class="doc-meta-hint">点击查看切片</span>
                  </div>
                </div>
                <div class="doc-actions" @click.stop>
                  <button v-if="doc.chunks > 0" class="doc-action-btn" type="button" :title="expandedDocId === doc.id ? '收起切片' : '查看切片'" @click="toggleDocChunks(doc)">
                    <svg viewBox="0 0 24 24" fill="none" width="14" height="14" :style="{ transform: expandedDocId === doc.id ? 'rotate(180deg)' : '', transition: 'transform .2s' }">
                      <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                  </button>
                  <button class="doc-action-btn danger" type="button" title="从知识库删除此文档" @click="handleDeleteDoc(doc)">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                      <path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 6V4h4v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                  </button>
                </div>
              </div>

              <!-- 切片展开区 -->
              <div v-if="expandedDocId === doc.id" class="doc-chunks-panel">
                <div v-if="docChunksCache[doc.id]?.loading" class="doc-chunks-loading">
                  <svg class="inline-spinner" viewBox="0 0 24 24" fill="none" width="13" height="13">
                    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
                  </svg>
                  加载切片中…
                </div>
                <div v-else-if="docChunksCache[doc.id]?.error" class="doc-chunks-error">
                  {{ docChunksCache[doc.id].error }}
                </div>
                <template v-else-if="docChunksCache[doc.id]?.chunks?.length">
                  <div class="doc-chunks-header">
                    共 {{ docChunksCache[doc.id].total }} 个切片，显示前 {{ docChunksCache[doc.id].showing }} 个
                  </div>
                  <div v-for="chunk in docChunksCache[doc.id].chunks" :key="chunk.id" class="doc-chunk-item">
                    <div class="doc-chunk-meta">
                      第 {{ chunk.index + 1 }} 片
                      <span v-if="chunk.tokenCount"> · {{ chunk.tokenCount }} tokens</span>
                    </div>
                    <div class="doc-chunk-content">{{ chunk.content }}</div>
                  </div>
                </template>
                <div v-else class="doc-chunks-empty">暂无切片数据</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 测试查询 Tab -->
        <div v-if="activeTab === 'query'" class="kb-query-panel">
          <div class="kb-query-input-row">
            <textarea
              v-model.trim="queryText"
              class="kb-query-input"
              placeholder="输入测试问题，验证知识库检索效果..."
              rows="3"
              @keydown.ctrl.enter.prevent="runQuery"
              @keydown.meta.enter.prevent="runQuery"
            ></textarea>
            <button class="kb-query-btn" type="button" :disabled="!queryText || queryLoading" @click="runQuery">
              <svg v-if="queryLoading" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" width="14" height="14">
                <path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
              {{ queryLoading ? '检索中…' : '检索' }}
            </button>
          </div>
          <div class="kb-query-hint">Ctrl+Enter 发送</div>

          <!-- 查询结果 -->
          <div v-if="queryResult" class="kb-query-result">
            <div class="kb-query-result-header">
              <span class="kb-query-confidence" :class="confidenceClass">
                置信度 {{ queryResult.confidence }}
              </span>
              <span v-if="!queryResult.answerFound" class="kb-query-no-answer">未找到相关内容</span>
            </div>
            <div class="kb-query-answer">{{ queryResult.answer }}</div>
            <div v-if="queryResult.citations?.length" class="kb-query-citations">
              <div class="kb-query-citations-title">引用来源：</div>
              <div v-for="(c, i) in queryResult.citations" :key="i" class="kb-query-citation">
                <div class="kb-citation-header">
                  <span class="kb-citation-source">{{ c.source }}</span>
                  <span class="kb-citation-score">相关度 {{ c.score }}</span>
                </div>
                <div class="kb-citation-snippet">{{ c.snippet }}</div>
              </div>
            </div>
          </div>
          <div v-if="queryError" class="kb-query-error">{{ queryError }}</div>
        </div>

        <!-- Fix 1: 成员管理 Tab -->
        <div v-if="activeTab === 'members'" class="kb-members-panel">
          <div class="kb-members-add-section">
            <div class="kb-members-add-title">添加成员</div>
            <div class="kb-member-search-row">
              <input
                v-model.trim="memberSearchKeyword"
                type="text"
                placeholder="搜索用户名…"
                class="kb-member-search-input"
                @input="onMemberSearch"
              />
              <select v-model="newMemberRole" class="kb-member-role-select-input">
                <option value="EDITOR">编辑者</option>
                <option value="VIEWER">只读</option>
              </select>
            </div>
            <div v-if="memberSearchResults.length" class="kb-member-search-dropdown">
              <div
                v-for="u in memberSearchResults"
                :key="u.userId"
                class="kb-member-search-item"
              >
                <span class="kb-member-search-avatar">{{ (u.username || 'U').slice(0, 1).toUpperCase() }}</span>
                <span class="kb-member-search-name">{{ u.username }}</span>
                <button class="kb-member-add-btn" type="button" @click="doAddKbMember(u)">+ 添加</button>
              </div>
            </div>
          </div>

          <div class="kb-members-list-section">
            <div class="kb-members-list-title">
              当前成员
              <span v-if="kb.kbMembers.length" class="kb-members-count">{{ kb.kbMembers.length }} 人</span>
            </div>
            <div v-if="!kb.kbMembers.length" class="kb-members-empty">暂无成员，搜索用户后可添加</div>
            <div v-else class="kb-member-list">
              <div v-for="m in kb.kbMembers" :key="m.userId" class="kb-member-row">
                <span class="kb-member-avatar-sm">{{ (m.username || 'U').slice(0, 1).toUpperCase() }}</span>
                <span class="kb-member-name">{{ m.username || m.userId }}</span>
                <span class="kb-member-role-badge" :class="'role-' + m.role.toLowerCase()">{{ kbRoleLabel(m.role) }}</span>
                <template v-if="canManageKb && m.role !== 'OWNER'">
                  <select
                    :value="m.role"
                    class="kb-member-role-edit"
                    @change="doChangeKbMemberRole(m, $event.target.value)"
                  >
                    <option value="EDITOR">编辑者</option>
                    <option value="VIEWER">只读</option>
                  </select>
                  <button class="kb-member-remove-btn" type="button" @click="doRemoveKbMember(m)">移除</button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useKbStore } from '../stores/kb.js';
import { useOrgStore } from '../stores/org.js';
import { useSessionStore } from '../stores/sessions.js';
import { useUiStore } from '../stores/ui.js';
import { formatFileSize, getFileIcon } from '../js/utils.js';
import * as api from '../services/api.js';

const kb     = useKbStore();
const org    = useOrgStore();
const sess   = useSessionStore();
const ui     = useUiStore();
const router = useRouter();

const fileInputEl   = ref(null);
const dragOver      = ref(false);

const expandedDocId = ref(null);
const docChunksCache = reactive({});
const hasKnowledgeBases = computed(() => kb.knowledgeBases.length > 0);

// Fix 7: KB 操作权限（org OWNER/ADMIN 可编辑删除 KB）
const canManageKb = computed(() =>
  ['OWNER', 'ADMIN'].includes(org.currentOrg?.role)
);

// Fix 5: 文档列表过滤 & 排序
const docStatusFilter = ref('');
const docSortBy       = ref('time_desc');
const filteredDocs = computed(() => {
  let list = [...kb.docs];
  if (docStatusFilter.value) {
    const f = docStatusFilter.value;
    list = list.filter(d =>
      f === 'PROCESSING'
        ? ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING'].includes(d.status)
        : d.status === f
    );
  }
  const sortMap = {
    time_desc: (a, b) => (a.uploadedAt < b.uploadedAt ? 1 : -1),
    time_asc:  (a, b) => (a.uploadedAt > b.uploadedAt ? 1 : -1),
    name_asc:  (a, b) => (a.filename   > b.filename   ? 1 : -1),
    size_desc: (a, b) => (a.size       < b.size        ? 1 : -1),
  };
  if (sortMap[docSortBy.value]) list.sort(sortMap[docSortBy.value]);
  return list;
});

// Fix 6: 是否有已完成/失败的上传任务
const hasCompletedUploads = computed(() =>
  kb.uploadQueue.some(t => ['done', 'error'].includes(t.status))
);

// Fix 13: KB 统计数据
const kbStats = ref(null);
watch(() => kb.currentKbId, async (id) => {
  if (!id) { kbStats.value = null; return; }
  try { kbStats.value = await api.getKbStats(id, org.currentOrgId); }
  catch { kbStats.value = null; }
}, { immediate: true });

// Fix 1: KB 成员管理
const memberSearchKeyword = ref('');
const memberSearchResults = ref([]);
const newMemberRole       = ref('EDITOR');
let _memberSearchTimer = null;

function kbRoleLabel(role) {
  return { OWNER: '拥有者', EDITOR: '编辑者', VIEWER: '只读' }[role] || role;
}

function switchToMembersTab() {
  activeTab.value = 'members';
  kb.loadKbMembers(org.currentOrgId);
}

function onMemberSearch() {
  clearTimeout(_memberSearchTimer);
  if (!memberSearchKeyword.value) { memberSearchResults.value = []; return; }
  _memberSearchTimer = setTimeout(async () => {
    try {
      const res = await api.searchUsers(memberSearchKeyword.value);
      memberSearchResults.value = (res || [])
        .filter(u => !kb.kbMembers.find(m => m.userId === u.userId))
        .slice(0, 6);
    } catch { memberSearchResults.value = []; }
  }, 300);
}

async function doAddKbMember(user) {
  try {
    await kb.addKbMember(user.userId, newMemberRole.value, org.currentOrgId);
    memberSearchKeyword.value = '';
    memberSearchResults.value = [];
    ui.showToast('success', `已添加「${user.username}」为${kbRoleLabel(newMemberRole.value)}`);
  } catch (err) {
    ui.showToast('error', err.message || '添加失败');
  }
}

async function doRemoveKbMember(member) {
  const ok = await ui.showConfirm({
    title: '移除成员',
    message: `确认将「${member.username || member.userId}」从知识库中移除？`,
    confirmText: '移除',
    variant: 'danger',
  });
  if (!ok) return;
  try {
    await kb.removeKbMember(member.userId, org.currentOrgId);
    ui.showToast('success', '成员已移除');
  } catch (err) {
    ui.showToast('error', err.message || '移除失败');
  }
}

async function doChangeKbMemberRole(member, newRole) {
  try {
    await kb.updateKbMemberRole(member.userId, newRole, org.currentOrgId);
    ui.showToast('success', `已将「${member.username}」调整为${kbRoleLabel(newRole)}`);
  } catch (err) {
    ui.showToast('error', err.message || '角色修改失败');
  }
}

async function toggleDocChunks(doc) {
  if (!['DONE'].includes(doc.status) && doc.chunks === 0) return;
  if (expandedDocId.value === doc.id) {
    expandedDocId.value = null;
    return;
  }
  expandedDocId.value = doc.id;
  if (docChunksCache[doc.id]) return;
  docChunksCache[doc.id] = { chunks: [], total: 0, showing: 0, loading: true };
  try {
    const res = await api.listDocumentChunks(kb.currentKbId, doc.id, org.currentOrgId, 20);
    docChunksCache[doc.id] = { ...res, loading: false };
  } catch (err) {
    docChunksCache[doc.id] = { chunks: [], total: 0, showing: 0, loading: false, error: err.message };
  }
}

const activeTab   = ref('docs');
const queryText   = ref('');
const queryLoading = ref(false);
const queryResult = ref(null);
const queryError  = ref('');

const confidenceClass = computed(() => {
  const c = parseFloat(queryResult.value?.confidence ?? 0);
  if (c >= 0.7) return 'confidence-high';
  if (c >= 0.4) return 'confidence-mid';
  return 'confidence-low';
});

const queueSummary = computed(() => {
  const total = kb.uploadQueue.length;
  const done  = kb.uploadQueue.filter(t => t.status === 'done').length;
  return `${done}/${total} 完成`;
});

const queueFinished = computed(() =>
  kb.uploadQueue.length > 0 && kb.uploadQueue.every(t => ['done','error'].includes(t.status))
);

function statusLabel(status) {
  const map = { PENDING: '等待', PARSING: '解析中', CHUNKING: '切片中', EMBEDDING: '向量化中', PROCESSING: '处理中' };
  return map[status] || status;
}

async function handleCreateKb() {
  const form = await ui.showForm({
    title: '新建知识库',
    confirmText: '创建',
    fields: [
      { key: 'name',        label: '知识库名称', placeholder: '例如：产品文档、客户案例、内部 SOP' },
      { key: 'description', label: '描述（可选）', placeholder: '描述知识库的用途', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try {
    await kb.createKb(form.name.trim(), form.description?.trim() || '', org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '创建失败');
  }
}

async function handleEditKb(item) {
  const form = await ui.showForm({
    title: '编辑知识库',
    confirmText: '保存',
    fields: [
      { key: 'name',        label: '知识库名称', placeholder: item.name,        defaultValue: item.name },
      { key: 'description', label: '描述（可选）', placeholder: item.description, defaultValue: item.description || '', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try {
    await kb.updateKb(item.id, form.name.trim(), form.description?.trim() || '', org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '更新失败');
  }
}

async function handleDeleteKb(item) {
  const confirmed = await ui.showConfirm({
    title: '删除知识库',
    message: `确认删除知识库「${item.name}」？\n此操作不可恢复，所有文档和切片将被永久删除。`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await kb.deleteKb(item.id, org.currentOrgId);
    ui.showToast('success', `已删除：${item.name}`);
  } catch (err) {
    ui.showToast('error', err.message || '删除失败');
  }
}

function triggerUpload() {
  if (!kb.currentKbId) { ui.showToast('warning', '请先选择或创建知识库'); return; }
  fileInputEl.value?.click();
}

function handleFileChange(event) {
  Array.from(event.target.files || []).forEach(f => kb.uploadFile(f, org.currentOrgId));
  event.target.value = '';
}

function handleDrop(event) {
  dragOver.value = false;
  if (!kb.currentKbId) { ui.showToast('warning', '请先选择或创建知识库'); return; }
  Array.from(event.dataTransfer.files || []).forEach(f => kb.uploadFile(f, org.currentOrgId));
}

async function handleDeleteDoc(doc) {
  const confirmed = await ui.showConfirm({
    title: '删除文档',
    message: `确认删除文档「${doc.filename}」？`,
    confirmText: '删除',
    variant: 'danger',
  });
  if (!confirmed) return;
  try {
    await api.deleteDocument(kb.currentKbId, doc.id, org.currentOrgId);
    ui.showToast('success', `已删除文档：${doc.filename}`);
    await kb.loadDocs(org.currentOrgId);
  } catch (err) {
    ui.showToast('error', err.message || '删除失败');
  }
}

let _searchTimer = null;


async function runQuery() {
  if (!queryText.value || queryLoading.value) return;
  queryLoading.value = true;
  queryResult.value  = null;
  queryError.value   = '';
  try {
    const res = await api.queryKnowledgeBase(kb.currentKbId, queryText.value, org.currentOrgId);
    queryResult.value = res;
  } catch (err) {
    queryError.value = err.message || '查询失败，请重试';
  } finally {
    queryLoading.value = false;
  }
}

// Fix 4: 关联后不强制跳转，Toast 提供行动按钮供用户自主选择
function useKbInChat() {
  sess.currentKbId = kb.currentKbId;
  ui.showToast('success', `已关联「${kb.currentKbName}」，可继续管理文档或前往对话`);
  // 不再强制 router.push('/chat')，让用户决定是否切换页面
}

// Fix 3: 重新解析失败的文档
async function retryDoc(doc) {
  try {
    await api.retryDocument(kb.currentKbId, doc.id, org.currentOrgId);
    doc.status = 'PENDING';
    doc.parseError = '';
    kb.startDocPolling(doc.id, org.currentOrgId);
    ui.showToast('success', `「${doc.filename}」已重新提交解析`);
  } catch (err) {
    ui.showToast('error', err.message || '重试失败，请稍后再试');
  }
}
</script>

<style scoped>
@import '../css/views/knowledge-base-view.css';
</style>
