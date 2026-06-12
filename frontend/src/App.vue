<template>
  <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
    <div class="sidebar-logo">
      <h1><span class="logo-icon"><LogoMark /></span>AI Agent</h1>
      <div class="sidebar-tools">
        <button class="icon-btn" type="button" title="搜索" @click="openSearch">
          <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        </button>
        <button class="icon-btn" type="button" title="收起侧边栏" @click="toggleSidebar">
          <svg viewBox="0 0 24 24" fill="none"><rect x="4" y="5" width="16" height="14" rx="3" stroke="currentColor" stroke-width="2"/><path d="M10 5v14M15 9l-3 3 3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </div>

    <div class="sidebar-section">
      <button class="new-chat-btn" type="button" @click="newSession">
        <svg viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2"/><path d="M12 8v8M8 12h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        开启新对话
      </button>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-section-title">最近</div>
    </div>
    <div class="session-list">
      <div v-if="sessions.length === 0" class="session-empty">暂无历史对话</div>
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === sessionId, generating: sessionRuntime[session.id]?.sending }"
        :title="session.title"
        @click="switchSession(session.id)"
      >
        <span class="session-icon">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" opacity=".5"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"/></svg>
        </span>
        <span class="session-title">{{ session.title }}</span>
        <!-- 回答中动态指示器 -->
        <span v-if="sessionRuntime[session.id]?.sending" class="session-generating">
          <span></span><span></span><span></span>
        </span>
        <button v-else class="session-delete" type="button" title="删除会话" @click.stop="removeSession(session.id)">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
        </button>
      </div>
    </div>

    <div class="sidebar-bottom">
      <div v-if="user" class="user-info" @click.stop="userMenuOpen = !userMenuOpen" style="cursor:pointer;position:relative;">
        <div class="user-avatar">{{ (user.username || 'U')[0].toUpperCase() }}</div>
        <div class="user-text">
          <div class="user-name">{{ user.username || user.userId }}</div>
          <div class="user-state">已登录</div>
        </div>
        <svg class="user-menu-arrow" viewBox="0 0 24 24" fill="none" width="14" height="14"
             :style="{ transform: userMenuOpen ? 'rotate(180deg)' : '', transition: 'transform .2s' }">
          <path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        <!-- 用户下拉菜单 -->
        <div v-if="userMenuOpen" class="user-dropdown" @click.stop>
          <button class="user-dropdown-item" type="button" @click="handleChangePassword">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" stroke="currentColor" stroke-width="1.8"/><path d="m9 12 2 2 4-4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
            修改密码
          </button>
          <button class="user-dropdown-item danger" type="button" @click="handleLogout">
            <svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            退出登录
          </button>
        </div>
      </div>
      <div class="sidebar-section-title model-title">当前模型</div>
      <div class="model-select" :class="{ open: modelMenuOpen }">
        <button class="model-select-trigger" type="button" @click="modelMenuOpen = !modelMenuOpen">
          <span class="model-dot"></span>
          <span>{{ currentModelLabel }}</span>
          <svg viewBox="0 0 24 24" fill="none"><path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <div v-if="modelMenuOpen" class="model-menu">
          <button
            v-for="option in modelOptions"
            :key="option.value"
            class="model-option"
            :class="{ active: model === option.value }"
            type="button"
            @click="selectModel(option.value)"
          >
            <span class="model-option-main">{{ option.label }}</span>
            <span class="model-option-desc">{{ option.desc }}</span>
            <svg v-if="model === option.value" viewBox="0 0 24 24" fill="none"><path d="m5 12 4 4L19 6" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </div>
      </div>
    </div>
  </aside>

  <button v-if="sidebarCollapsed" class="sidebar-expand-btn" type="button" title="展开侧边栏" @click="toggleSidebar">
    <svg viewBox="0 0 24 24" fill="none"><rect x="4" y="5" width="16" height="14" rx="3" stroke="currentColor" stroke-width="2"/><path d="M10 5v14M13 9l3 3-3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
  </button>

  <main class="main" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <div class="topbar">
      <div class="topbar-title">{{ currentSessionTitle }}</div>
      <div class="topbar-actions">
        <button class="topbar-btn danger" type="button" @click="handleClearMemory">清除记忆</button>
        <button class="topbar-btn" type="button" @click="activeTab = 'kb'">知识库</button>
      </div>
    </div>

<div class="tabs">
<button v-for="tab in tabs" :key="tab.key" class="tab" :class="{ active: activeTab === tab.key }" type="button" @click="switchTab(tab.key)">
{{ tab.label }}
</button>
</div>

    <div class="content">
      <section class="tab-panel" :class="{ active: activeTab === 'chat' }">
        <div ref="chatMessagesEl" class="chat-messages">
            <div v-if="messages.length === 0" class="welcome">
            <div class="welcome-icon"><LogoMark /></div>
            <h2>使用{{ reactEnabled ? '专家模式' : '快速模式' }}开始对话</h2>
            <div class="welcome-modes">
              <button class="welcome-mode" :class="{ active: !reactEnabled }" type="button" @click="setChatMode('quick')">
                <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
                快速模式
              </button>
              <button class="welcome-mode" :class="{ active: reactEnabled }" type="button" @click="setChatMode('expert')">
                <svg viewBox="0 0 24 24" fill="none"><path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/><path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/></svg>
                专家模式
              </button>
            </div>
            <!-- 快捷提示词 -->
            <div class="welcome-prompts">
              <button
                v-for="p in quickPrompts"
                :key="p.label"
                class="welcome-prompt-btn"
                type="button"
                @click="sendQuick(p.message)"
              >
                <span class="welcome-prompt-icon" v-html="p.icon"></span>
                <span>{{ p.label }}</span>
              </button>
            </div>
          </div>

          <div v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <div v-if="message.role === 'ai'" class="avatar ai">AI</div>
            <div class="bubble" v-html="message.html"></div>
          </div>
        </div>

        <div class="chat-input-area">
          <div class="input-wrapper">
            <textarea
              id="messageInput"
              ref="messageInputEl"
              v-model="messageInput"
              :disabled="currentSessionSending"
              :placeholder="enterToSend ? '输入消息，Enter 发送，Shift+Enter 换行...' : '输入消息，Ctrl+Enter 发送...'"
              rows="1"
              @input="autoResize"
              @keydown="handleInputKeydown"
            ></textarea>
            <div class="composer-footer">
              <div class="composer-tools">
                <button class="quick-prompt tool-chip" type="button" @click="setChatMode('quick')" :class="{ active: !reactEnabled }">
                  <svg viewBox="0 0 24 24" fill="currentColor"><path d="m13 2-8 12h6l-1 8 9-13h-6l1-7Z"/></svg>
                  快速模式
                </button>
                <button class="quick-prompt tool-chip" type="button" @click="setChatMode('expert')" :class="{ active: reactEnabled }">
                  <svg viewBox="0 0 24 24" fill="none"><path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/><path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/></svg>
                  专家模式
                </button>
                <button class="quick-prompt tool-chip" type="button" @click="enterToSend = !enterToSend" :class="{ active: enterToSend }" :title="enterToSend ? '当前：Enter 发送，点击切换为 Ctrl+Enter' : '当前：Ctrl+Enter 发送，点击切换为 Enter'">
                  <svg viewBox="0 0 24 24" fill="none" width="12" height="12"><path d="M20 6H4M4 12h10M4 18h6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="m16 15 3 3-3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  {{ enterToSend ? 'Enter 发送' : 'Ctrl+Enter' }}
                </button>
              </div>
              <div class="composer-actions">
                <button class="attach-btn" :class="{ active: currentKbId }" type="button" title="关联知识库" @click="handleAttachKb">
                  <svg viewBox="0 0 24 24" fill="none"><path d="m20 11.5-7.7 7.7a5.2 5.2 0 0 1-7.4-7.4l8.4-8.4a3.6 3.6 0 0 1 5.1 5.1l-8.4 8.4a2 2 0 0 1-2.8-2.8l7.6-7.6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </button>
                <button v-if="currentSessionSending" class="stop-btn" type="button" title="停止生成" @click="stopGeneration">
                  <svg viewBox="0 0 24 24" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>
                </button>
                <button v-else class="send-btn" type="button" :disabled="!messageInput.trim()" @click="sendMessage">
                  <svg viewBox="0 0 24 24" fill="none"><path d="M12 19V5M6 11l6-6 6 6" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </button>
              </div>
            </div>
          </div>
          <div class="input-hints">
            <span v-if="currentKbId" class="kb-active-badge">
              <svg viewBox="0 0 24 24" fill="none" width="11" height="11"><path d="M4 19V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7M4 19h16M4 19a2 2 0 0 1-2-2v-1h20v1a2 2 0 0 1-2 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
              {{ currentKbName }}
              <button class="kb-active-clear" type="button" title="取消关联知识库" @click.stop="currentKbId = null">×</button>
            </span>
            <span class="hint-text">{{ enterToSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}</span>
          </div>
        </div>
      </section>

      <section class="tab-panel" :class="{ active: activeTab === 'kb' }">
        <div class="kb-panel">
          <div class="kb-selector-area">
            <div class="kb-selector-header">
              <div class="kb-section-title-row">
                <h3 class="kb-section-title">知识库</h3>
                <span class="kb-org-badge" :title="'当前组织：' + currentOrgName">{{ currentOrgName }}</span>
              </div>
              <button class="kb-create-btn" type="button" @click="handleCreateKb">+ 新建</button>
            </div>
            <div class="kb-list">
              <div v-if="knowledgeBases.length === 0" class="kb-empty-hint">暂无知识库，点击上方按钮创建</div>
              <div
                v-for="kb in knowledgeBases"
                :key="kb.id"
                class="kb-item"
                :class="{ active: kb.id === currentKbId }"
                @click="selectKb(kb.id)"
              >
                <div class="kb-item-icon"></div>
                <div class="kb-item-info">
                  <div class="kb-item-name">{{ kb.name }}</div>
                  <div class="kb-item-meta">{{ kb.docCount || 0 }} 篇文档</div>
                </div>
                <button class="kb-item-edit" type="button" title="编辑知识库" @click.stop="handleEditKb(kb)">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </button>
                <button class="kb-item-delete" type="button" title="删除知识库" @click.stop="handleDeleteKb(kb.id)"></button>
              </div>
            </div>
          </div>

          <div class="kb-current-area">
            <div class="kb-current-header">
              <h3 class="kb-section-title">文档管理</h3>
              <button v-if="currentKbId" class="kb-manage-members-btn" type="button" title="管理知识库成员" @click="openKbMembers">成员</button>
            </div>

            <div
              class="kb-upload-area"
              :class="{ 'drag-over': dragOver }"
              :style="{ opacity: currentKbId ? '1' : '0.5', pointerEvents: currentKbId ? 'auto' : 'none' }"
              @click="triggerUpload"
              @dragover.prevent="dragOver = true"
              @dragleave="dragOver = false"
              @drop.prevent="handleDrop"
            >
              <div class="kb-upload-icon">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="12" y1="18" x2="12" y2="12"/>
                  <line x1="9" y1="15" x2="15" y2="15"/>
                </svg>
              </div>
              <div class="kb-upload-title">上传文档到当前知识库</div>
              <div class="kb-upload-desc">支持 PDF、Word、TXT、Markdown 等格式 · 拖拽或点击上传 · 最大 50MB</div>
              <button class="upload-btn" type="button">选择文件</button>
              <input ref="fileInputEl" id="fileInput" type="file" multiple accept=".pdf,.doc,.docx,.txt,.md" @change="handleFileChange">
            </div>

            <!-- ===== 上传进度区 ===== -->
            <div v-if="uploadQueue.length > 0" class="upload-queue">
              <div class="upload-queue-header">
                <span>{{ uploadQueueSummary }}</span>
                <span class="upload-queue-done" v-if="uploadQueueFinished">
                  <svg viewBox="0 0 24 24" fill="none" width="13" height="13"><path d="m5 12 4 4L19 6" stroke="#00A96E" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  全部完成
                </span>
              </div>
              <div v-for="task in uploadQueue" :key="task.id" class="upload-task">
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
                  <div class="upload-task-fill"
                    :class="`fill-${task.status}`"
                    :style="{ width: task.barWidth + '%' }">
                  </div>
                </div>
                <div v-if="task.status === 'uploading'" class="upload-task-meta">
                  <span>{{ task.speedText }}</span>
                  <span v-if="task.etaText">剩余 {{ task.etaText }}</span>
                  <span>{{ task.loadedText }} / {{ task.totalText }}</span>
                </div>
                <div v-if="task.status === 'error'" class="upload-task-error">{{ task.error }}</div>
              </div>
            </div>

            <div class="kb-docs-title">已导入文档</div>
            <div>
              <div v-if="!currentKbId" class="empty-docs">请先选择一个知识库</div>
              <div v-else-if="docs.length === 0" class="empty-docs">暂无文档，上传后 AI 可基于文档内容回答</div>
              <div v-for="doc in docs" :key="doc.id" class="doc-item">
                <div class="doc-icon" :class="getFileIcon(doc.filename).cls" v-html="getFileIcon(doc.filename).icon"></div>
                <div class="doc-info">
                  <div class="doc-name" :title="doc.filename">{{ doc.filename }}</div>
                  <div class="doc-meta">{{ statusLabel(doc.status) }} {{ doc.chunks }} 个切片{{ doc.size ? ` · ${formatFileSize(doc.size)}` : '' }} · {{ doc.uploadedAt }}</div>
                </div>
                <div class="doc-actions">
                  <button class="doc-delete" type="button" title="从知识库删除此文档" @click="handleDeleteDoc(doc)"></button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="kbMembersVisible" class="kb-members-panel">
            <div class="kb-members-header">
              <h3>知识库成员</h3>
              <button class="kb-members-close" type="button" @click="kbMembersVisible = false">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
              </button>
            </div>
            <div class="kb-members-add">
              <div class="kb-member-search-wrap">
                <input
                  v-model.trim="kbMemberUsername"
                  type="text"
                  placeholder="输入用户名搜索..."
                  class="kb-member-input"
                  autocomplete="off"
                  @input="onKbMemberSearchInput"
                  @blur="hideKbMemberSuggestions"
                  @focus="onKbMemberSearchInput"
                >
                <div v-if="kbMemberSuggestions.length > 0 && kbMemberSuggestionsVisible"
                     class="kb-member-suggestions">
                  <button
                    v-for="u in kbMemberSuggestions"
                    :key="u.userId"
                    class="kb-member-suggestion-item"
                    type="button"
                    @mousedown.prevent="selectKbMemberSuggestion(u)"
                  >
                    <span class="kb-member-sug-name">{{ u.username }}</span>
                    <span class="kb-member-sug-id">{{ u.userId }}</span>
                  </button>
                </div>
              </div>
              <select v-model="kbMemberRole" class="kb-member-role-select">
                <option value="VIEWER">只读（VIEWER）</option>
                <option value="EDITOR">编辑（EDITOR）</option>
              </select>
              <button class="kb-member-add-btn" type="button" @click="addMemberToCurrentKb">添加</button>
            </div>
            <div class="kb-members-list">
              <div v-if="kbMembers.length === 0" class="empty-hint">暂无成员</div>
              <div v-for="member in kbMembers" :key="member.userId" class="kb-member-item">
                <span class="kb-member-id">
                  {{ member.username || member.userId }}
                  <small v-if="member.username" class="kb-member-sub-id">{{ member.userId }}</small>
                </span>
                <!-- OWNER 角色只读展示，其余可修改 -->
                <template v-if="member.role === 'OWNER'">
                  <span class="kb-member-role owner-badge">{{ kbRoleLabel(member.role) }}</span>
                </template>
                <template v-else>
                  <select
                    class="kb-member-role-inline"
                    :value="member.role"
                    @change="changeKbMemberRole(member.userId, $event.target.value)"
                  >
                    <option value="VIEWER">只读</option>
                    <option value="EDITOR">编辑</option>
                  </select>
                  <button class="kb-member-remove-btn" type="button" title="移除成员"
                    @click="removeKbMemberFromPanel(member.userId)">
                    <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
                  </button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="tab-panel" :class="{ active: activeTab === 'org' }">
        <div class="kb-panel">
          <div class="org-panel">
            <div class="org-header">
              <h3 class="org-section-title">组织管理</h3>
              <button class="org-create-btn" type="button" @click="handleCreateOrg">+ 创建企业组织</button>
            </div>
            <div class="org-desc">组织是多租户的基本单位。个人用户自动拥有「个人空间」，企业可创建组织邀请员工共享知识库。<br><strong>点击组织可切换，知识库 Tab 将自动显示该组织的知识库。</strong></div>
            <div class="org-list">
              <div v-if="organizations.length === 0" class="org-empty-hint">暂无组织</div>
              <div
                v-for="org in organizations"
                :key="org.orgId"
                class="org-item"
                :class="{ active: org.orgId === currentOrgId }"
                @click="selectOrg(org.orgId)"
              >
                <div class="org-item-icon">{{ org.orgType === 'PERSONAL' ? '个人' : '企业' }}</div>
                <div class="org-item-info">
                  <div class="org-item-name">{{ org.orgType === 'PERSONAL' ? '个人空间' : (org.name || org.orgId) }}</div>
                  <div class="org-item-meta">
                    {{ orgRoleLabel(org.role) }}
                    <template v-if="org.orgId === currentOrgId">
                      <span class="org-item-kb-count">· 知识库 {{ knowledgeBases.length }} 个</span>
                    </template>
                  </div>
                </div>
                <span v-if="org.orgId === currentOrgId" class="org-item-active-badge">当前</span>
                <!-- 企业组织操作按钮 -->
                <div v-if="org.orgType === 'ENTERPRISE'" class="org-item-actions" @click.stop>
                  <button v-if="org.role === 'OWNER'" class="org-item-action-btn" type="button"
                    title="编辑组织" @click.stop="handleEditOrg(org)">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  </button>
                  <button v-if="org.role !== 'OWNER'" class="org-item-action-btn danger" type="button"
                    title="退出组织" @click.stop="handleLeaveOrg(org)">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  </button>
                  <button v-if="org.role === 'OWNER'" class="org-item-action-btn danger" type="button"
                    title="删除组织" @click.stop="handleDeleteOrg(org)">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
                  </button>
                </div>
              </div>
            </div>
            <div v-if="currentOrgId" class="org-actions">
              <h4 class="org-section-title">组织操作</h4>
              <!-- 企业组织：邀请成员（用户名搜索）+ 查看成员 -->
              <template v-if="currentOrg?.orgType === 'ENTERPRISE'">
                <!-- 邀请成员：用户名搜索输入框 -->
                <div class="org-invite-wrap">
                  <div class="kb-member-search-wrap">
                    <input
                      v-model.trim="orgInviteUsername"
                      type="text"
                      placeholder="输入用户名搜索..."
                      class="kb-member-input"
                      autocomplete="off"
                      @input="onOrgInviteSearchInput"
                      @blur="hideOrgInviteSuggestions"
                      @focus="onOrgInviteSearchInput"
                    >
                    <div v-if="orgInviteSuggestions.length > 0 && orgInviteSuggestionsVisible"
                         class="kb-member-suggestions">
                      <button
                        v-for="u in orgInviteSuggestions"
                        :key="u.userId"
                        class="kb-member-suggestion-item"
                        type="button"
                        @mousedown.prevent="selectOrgInviteSuggestion(u)"
                      >
                        <span class="kb-member-sug-name">{{ u.username }}</span>
                        <span class="kb-member-sug-id">{{ u.userId }}</span>
                      </button>
                    </div>
                  </div>
                  <select v-model="orgInviteRole" class="kb-member-role-select">
                    <option value="MEMBER">成员</option>
                    <option value="ADMIN">管理员</option>
                  </select>
                  <button class="kb-member-add-btn" type="button" @click="doInviteOrgMember">邀请</button>
                </div>
                <button class="org-action-btn" type="button" @click="showOrgMembers(currentOrgId)">查看成员</button>
              </template>
              <template v-else>
                <p class="org-personal-hint">个人空间为私有隔离空间，不支持邀请成员。<br>如需多人协作，请创建企业组织。</p>
              </template>
            </div>
          </div>
        </div>
      </section>

      <!-- ===== 监控 Tab ===== -->
      <section class="tab-panel" :class="{ active: activeTab === 'monitor' }">
        <div class="kb-panel monitor-panel">

          <!-- 个人今日用量卡片 -->
          <div class="monitor-section">
            <div class="monitor-section-title">
              <span>我的今日用量</span>
              <button class="monitor-refresh-btn" type="button" @click="loadMyUsage" :disabled="monitorLoading.my">
                <svg viewBox="0 0 24 24" fill="none" width="13" height="13" :class="{ spinning: monitorLoading.my }">
                  <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                  <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                  <path d="M8 16H3v5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                刷新
              </button>
            </div>
            <div class="monitor-cards">
              <div class="monitor-card">
                <div class="monitor-card-value">${{ monitorData.myCost ?? '—' }}</div>
                <div class="monitor-card-label">今日消费（USD）</div>
              </div>
            </div>
          </div>

          <!-- 管理员区：全局总览 -->
          <div v-if="isAdmin" class="monitor-section">
            <div class="monitor-section-title">
              <span>全局总览（管理员）</span>
              <div class="monitor-period-btns">
                <button v-for="d in [7, 14, 30]" :key="d" class="monitor-period-btn"
                  :class="{ active: monitorDays === d }" type="button" @click="monitorDays = d; loadAdminStats()">
                  近{{ d }}天
                </button>
              </div>
              <button class="monitor-refresh-btn" type="button" @click="loadAdminStats" :disabled="monitorLoading.admin">
                <svg viewBox="0 0 24 24" fill="none" width="13" height="13" :class="{ spinning: monitorLoading.admin }">
                  <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                  <path d="M21 3v5h-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                  <path d="M8 16H3v5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                刷新
              </button>
            </div>
            <div class="monitor-cards">
              <div class="monitor-card">
                <div class="monitor-card-value">${{ monitorData.todayCost ?? '—' }}</div>
                <div class="monitor-card-label">今日总消费（USD）</div>
              </div>
              <div class="monitor-card" :class="{ 'monitor-card-warn': monitorData.errorRate > 0.05 }">
                <div class="monitor-card-value">{{ monitorData.errorPct ?? '—' }}</div>
                <div class="monitor-card-label">近5分钟错误率</div>
              </div>
            </div>

            <!-- 图表区 -->
            <div class="monitor-charts-row">
              <div class="monitor-chart-card">
                <div class="monitor-chart-title">费用趋势（近 {{ monitorDays }} 天）</div>
                <canvas ref="costChartEl" height="180"></canvas>
              </div>
              <div class="monitor-chart-card">
                <div class="monitor-chart-title">模型消费占比</div>
                <canvas ref="modelPieEl" height="180"></canvas>
              </div>
            </div>

            <!-- 按模型统计 -->
            <div class="monitor-table-title">按模型统计（近 {{ monitorDays }} 天）</div>
            <div v-if="!monitorData.modelReport?.length" class="empty-hint">暂无数据</div>
            <table v-else class="monitor-table">
              <thead><tr><th>模型</th><th>输入 Token</th><th>输出 Token</th><th>费用（USD）</th></tr></thead>
              <tbody>
                <tr v-for="row in monitorData.modelReport" :key="row.modelName">
                  <td>{{ row.modelName }}</td>
                  <td>{{ fmtNum(row.inputTokens) }}</td>
                  <td>{{ fmtNum(row.outputTokens) }}</td>
                  <td>${{ fmtCost(row.costUsd) }}</td>
                </tr>
              </tbody>
            </table>

            <!-- 按用户 TopN -->
            <div class="monitor-table-title">用户消费排行（近 {{ monitorDays }} 天）</div>
            <div v-if="!monitorData.userReport?.length" class="empty-hint">暂无数据</div>
            <table v-else class="monitor-table">
              <thead><tr><th>用户 ID</th><th>总 Token</th><th>费用（USD）</th></tr></thead>
              <tbody>
                <tr v-for="row in monitorData.userReport" :key="row.userId">
                  <td class="user-id-cell">{{ row.userId }}</td>
                  <td>{{ fmtNum(row.totalTokens) }}</td>
                  <td>${{ fmtCost(row.costUsd) }}</td>
                </tr>
              </tbody>
            </table>

            <!-- 管理员用户管理 -->
            <div class="monitor-section-title" style="margin-top:20px;">
              <span>用户管理</span>
              <button class="monitor-refresh-btn" type="button" @click="loadAdminUsers">刷新</button>
            </div>
            <div v-if="!adminUsers.items?.length" class="empty-hint">暂无数据</div>
            <table v-else class="monitor-table">
              <thead><tr><th>用户名</th><th>用户 ID</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="u in adminUsers.items" :key="u.userId">
                  <td>{{ u.username }}</td>
                  <td class="user-id-cell">{{ u.userId }}</td>
                  <td>{{ u.roles?.join(', ') }}</td>
                  <td><span :class="u.enabled ? 'status-enabled' : 'status-disabled'">{{ u.enabled ? '正常' : '禁用' }}</span></td>
                  <td>
                    <button v-if="u.enabled" class="admin-user-btn danger" type="button" @click="handleDisableUser(u.userId)">禁用</button>
                    <button v-else class="admin-user-btn" type="button" @click="handleEnableUser(u.userId)">启用</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-if="adminUsers.totalPages > 1" class="monitor-pagination">
              <button :disabled="adminUserPage === 0" class="monitor-page-btn" type="button" @click="adminUserPage--; loadAdminUsers()">上一页</button>
              <span>第 {{ adminUserPage + 1 }} / {{ adminUsers.totalPages }} 页</span>
              <button :disabled="adminUserPage >= adminUsers.totalPages - 1" class="monitor-page-btn" type="button" @click="adminUserPage++; loadAdminUsers()">下一页</button>
            </div>
          </div>

        </div>
      </section>
    </div>
  </main>

  <div class="toast-container">
    <div v-for="toast in toasts" :key="toast.id" class="toast toast-visible" :class="`toast-${toast.type}`">
      <span class="toast-icon" v-html="toastIcon(toast.type)"></span>
      <span class="toast-msg">{{ toast.message }}</span>
      <button class="toast-close" type="button" @click="dismissToast(toast.id)">×</button>
    </div>
  </div>

  <div v-if="orgModal.visible" class="modal-overlay" @click.self="orgModal.visible = false">
    <div class="modal-content">
      <div class="modal-header">
        <h3>{{ orgModal.title }}</h3>
        <button class="modal-close" type="button" @click="orgModal.visible = false">×</button>
      </div>
      <div class="modal-body">
        <div v-if="orgModal.members.length === 0" class="empty-hint">暂无成员</div>
        <div v-for="member in orgModal.members" :key="member.userId" class="member-item">
          <div class="member-info">
            <!-- 优先显示 username，其次显示 userId -->
            <div class="member-name">{{ member.username || member.userId }}</div>
            <div class="member-id" v-if="member.username">{{ member.userId }}</div>
          </div>
          <!-- OWNER 角色只读；其余成员可由 OWNER/ADMIN 修改角色 -->
          <template v-if="member.role === 'OWNER'">
            <span class="member-role owner-badge">{{ orgRoleLabel(member.role) }}</span>
          </template>
          <template v-else>
            <select
              class="kb-member-role-inline"
              :value="member.role"
              @change="changeOrgMemberRole(member.userId, $event.target.value)"
            >
              <option value="MEMBER">成员</option>
              <option value="ADMIN">管理员</option>
            </select>
          </template>
          <button v-if="member.role !== 'OWNER'" class="member-remove" type="button"
            @click="removeOrgMemberFromModal(member.userId)">移除</button>
        </div>
      </div>
    </div>
  </div>

  <div v-if="searchVisible" class="search-overlay" @click.self="closeSearch">
    <div class="search-modal">
      <div class="search-bar">
        <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        <input
          ref="searchInputEl"
          v-model.trim="searchQuery"
          type="text"
          placeholder="搜索历史对话"
          @keydown.esc.prevent="closeSearch"
        >
        <button class="search-close" type="button" title="关闭" @click="closeSearch">
          <svg viewBox="0 0 24 24" fill="none"><path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        </button>
      </div>
      <div class="search-results">
        <button
          v-for="session in filteredSessions"
          :key="session.id"
          class="search-result"
          :class="{ active: session.id === sessionId }"
          type="button"
          @click="openSearchResult(session.id)"
        >
          <span class="search-result-icon">
            <svg viewBox="0 0 24 24" fill="none"><path d="M8 10h8M8 14h5M6.5 19A7.5 7.5 0 1 1 18 17.7L21 20l-1.3 1.5-3.1-2.3A7.5 7.5 0 0 1 6.5 19Z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <span class="search-result-main">
            <span class="search-result-title">{{ session.title }}</span>
            <span class="search-result-snippet">{{ searchResultSnippet(session) }}</span>
          </span>
          <span class="search-result-date">{{ formatSessionDate(session.createdAt) }}</span>
        </button>
        <div v-if="filteredSessions.length === 0" class="search-empty">
          <div class="search-empty-icon">
            <svg viewBox="0 0 24 24" fill="none"><path d="m21 21-4.2-4.2m2.2-5.3a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
          </div>
          <div>没有找到相关对话</div>
        </div>
      </div>
    </div>
  </div>

  <div v-if="dialog.visible" class="modal-overlay app-dialog-overlay" @click.self="resolveDialog(false)">
    <div class="modal-content app-dialog" :class="`dialog-${dialog.variant}`">
      <div class="dialog-icon">
        <svg v-if="dialog.variant === 'danger'" viewBox="0 0 24 24" fill="none"><path d="M12 8v5M12 17h.01M10.3 4.2 2.8 17.1A2 2 0 0 0 4.5 20h15a2 2 0 0 0 1.7-2.9L13.7 4.2a2 2 0 0 0-3.4 0Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        <svg v-else viewBox="0 0 24 24" fill="none"><path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/><path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/></svg>
      </div>
      <div class="dialog-main">
        <div class="dialog-header">
          <h3>{{ dialog.title }}</h3>
          <button class="modal-close" type="button" @click="resolveDialog(false)">×</button>
        </div>
        <p v-if="dialog.message" class="dialog-message">{{ dialog.message }}</p>
        <div v-if="dialog.type === 'prompt'" class="dialog-fields">
          <label class="dialog-field-label" :for="dialog.inputId">{{ dialog.inputLabel }}</label>
          <input
            :id="dialog.inputId"
            v-model.trim="dialog.inputValue"
            class="dialog-input"
            type="text"
            :placeholder="dialog.placeholder"
            @keydown.enter.prevent="resolveDialog(true)"
            @keydown.esc.prevent="resolveDialog(false)"
          >
        </div>
        <div v-if="dialog.type === 'form'" class="dialog-fields">
          <label
            v-for="field in dialog.fields"
            :key="field.key"
            class="dialog-field"
          >
            <span class="dialog-field-label">{{ field.label }}</span>
            <textarea
              v-if="field.multiline"
              v-model.trim="dialog.formValues[field.key]"
              class="dialog-input dialog-textarea"
              :placeholder="field.placeholder"
              rows="3"
              @keydown.esc.prevent="resolveDialog(false)"
            ></textarea>
            <input
              v-else
              v-model.trim="dialog.formValues[field.key]"
              class="dialog-input"
              :type="field.type || 'text'"
              :placeholder="field.placeholder"
              @keydown.enter.prevent="resolveDialog(true)"
              @keydown.esc.prevent="resolveDialog(false)"
            >
          </label>
        </div>
        <div v-if="dialog.type === 'choice'" class="dialog-choice-list">
          <button
            v-for="choice in dialog.choices"
            :key="choice.value"
            class="dialog-choice"
            :class="{ active: dialog.choiceValue === choice.value }"
            type="button"
            @click="dialog.choiceValue = choice.value"
          >
            <span>{{ choice.label }}</span>
            <small>{{ choice.desc }}</small>
          </button>
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn secondary" type="button" @click="resolveDialog(false)">{{ dialog.cancelText }}</button>
          <button class="dialog-btn primary" :class="{ danger: dialog.variant === 'danger' }" type="button" @click="resolveDialog(true)">
            {{ dialog.confirmText }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, defineComponent, h, nextTick, onMounted, onUnmounted, reactive, ref, watch} from 'vue';
import * as api from './services/api.js';
import {formatFileSize, formatMarkdown, getFileIcon} from './js/utils.js';
import { Chart, LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
Chart.register(LineController, BarController, DoughnutController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend, Filler);

const LogoMark = defineComponent({
  setup() {
    return () => h('svg', { viewBox: '0 0 32 32', fill: 'currentColor', xmlns: 'http://www.w3.org/2000/svg' }, [
      h('path', { d: 'M27.6 11.8c-1.8.2-3.4-.2-4.8-1.1-1.9-1.3-3-3.3-3.5-5.9-.1-.6-.8-.9-1.3-.5-2.5 1.7-4 4-4.4 6.9-2.2-1.2-4.9-1.5-8-.9-.6.1-.9.8-.6 1.3 1.4 2.6 3.3 4.6 5.7 5.9-1.2.8-2.5 1.1-3.9 1.1-.7 0-1.1.8-.7 1.4 2 3.3 5.4 5.2 9.7 5.2 6.1 0 10.7-3.8 11.6-9.2.6-.6 1.1-1.4 1.5-2.3.4-.9-.2-2-1.3-1.9Zm-8 6.6c-1.9 1.6-4.5 1.8-6.5.4 1.7-.4 3-1.2 4-2.5 1.4.7 3 .9 4.7.6-.5.6-1.2 1.1-2.2 1.5Z' })
    ]);
  }
});

const tabs = [
{ key: 'chat', label: '对话' },
{ key: 'kb', label: '知识库' },
{ key: 'org', label: '组织' },
{ key: 'monitor', label: '监控' }
];
const quickPrompts = [
  {
    label: '查询订单状态',
    message: '帮我查一下订单 #12345 的状态',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  },
  {
    label: '查询今日天气',
    message: '北京今天天气怎么样？',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  },
  {
    label: '了解我的功能',
    message: '帮我介绍一下你能做什么',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  },
  {
    label: '查询账户余额',
    message: '查询用户 U001 的账户余额',
    icon: '<svg viewBox="0 0 24 24" fill="none" width="14" height="14"><rect x="2" y="5" width="20" height="14" rx="2" stroke="currentColor" stroke-width="2"/><path d="M2 10h20" stroke="currentColor" stroke-width="2"/></svg>'
  }
];

const user = ref(api.getUser());
const model = ref('deepseek');
const modelMenuOpen = ref(false);
const searchVisible = ref(false);
const searchQuery = ref('');
const sidebarCollapsed = ref(false);
const activeTab = ref('chat');
const sessionId = ref(generateId());
const sessions = ref([]);
const sessionMessages = reactive({});

// ── 会话持久化（localStorage）─────────────────────────────────────
// 按用户隔离：key 中带上 userId，防止多用户共用浏览器互看历史
const STORAGE_KEY_PREFIX = 'ai_agent_sessions';
const MAX_MESSAGES_PER_SESSION = 100; // 每个会话最多保留消息数
const MAX_SESSIONS_STORED = 50;       // 最多持久化的会话数
let _saveTimer = null;
function scheduleSave() {
  clearTimeout(_saveTimer);
  _saveTimer = setTimeout(saveSessions, 200);
}

function getStorageKey() {
  const uid = user.value?.userId || 'guest';
  return `${STORAGE_KEY_PREFIX}_${uid}`;
}

function saveSessions() {
  try {
    const key = getStorageKey();
    // 只保留最近的 MAX_SESSIONS_STORED 个会话
    const sessionsToSave = sessions.value.slice(0, MAX_SESSIONS_STORED);
    const msgMap = {};
    sessionsToSave.forEach(s => {
      const msgs = sessionMessages[s.id] || [];
      // 每个会话只保留最近 MAX_MESSAGES_PER_SESSION 条（避免 localStorage 超限）
      msgMap[s.id] = msgs.slice(-MAX_MESSAGES_PER_SESSION);
    });
    localStorage.setItem(key, JSON.stringify({
      sessions: sessionsToSave,
      messages: msgMap,
      activeSessionId: sessionId.value
    }));
  } catch (e) {
    // localStorage 可能超限（通常 5MB），静默失败
    console.warn('会话持久化失败:', e.message);
  }
}

function loadSessions() {
  try {
    const key = getStorageKey();
    const raw = localStorage.getItem(key);
    if (!raw) return false;
    const data = JSON.parse(raw);
    if (!data?.sessions?.length) return false;
    // 恢复会话列表
    sessions.value = data.sessions;
    // 恢复消息
    Object.entries(data.messages || {}).forEach(([id, msgs]) => {
      sessionMessages[id] = msgs;
    });
    // 恢复上次激活的会话
    const lastId = data.activeSessionId;
    if (lastId && sessions.value.some(s => s.id === lastId)) {
      sessionId.value = lastId;
    } else {
      sessionId.value = sessions.value[0].id;
    }
    messages.value = sessionMessages[sessionId.value] || [];
    return true;
  } catch (e) {
    console.warn('恢复会话失败:', e.message);
    return false;
  }
}
const messages = ref([]);
const messageInput = ref('');
const streamEnabled = ref(true);
const reactEnabled = ref(false);
const enterToSend = ref(false); // 默认 Ctrl+Enter 发送，可切换为 Enter 发送
const sessionRuntime = reactive({});
const chatMessagesEl = ref(null);
const messageInputEl = ref(null);
const searchInputEl = ref(null);

const knowledgeBases = ref([]);
const currentKbId = ref(null);
const docs = ref([]);
const fileInputEl = ref(null);
const dragOver = ref(false);
// 多文件上传队列
const uploadQueue = ref([]);
const kbMembersVisible = ref(false);
const kbMembers = ref([]);
const kbMemberUserId = ref('');      // 最终选定的 userId
const kbMemberUsername = ref('');    // 搜索输入框的文字
const kbMemberRole = ref('VIEWER');
const kbMemberSuggestions = ref([]);
const kbMemberSuggestionsVisible = ref(false);
let _kbSearchTimer = null;

const organizations = ref([]);
const currentOrgId = ref(null);
const orgModal = reactive({ visible: false, title: '', orgId: '', members: [] });

// 组织邀请成员搜索
const orgInviteUserId = ref('');       // 选定后的 userId
const orgInviteUsername = ref('');     // 搜索输入框内容
const orgInviteRole = ref('MEMBER');
const orgInviteSuggestions = ref([]);
const orgInviteSuggestionsVisible = ref(false);
let _orgInviteSearchTimer = null;

// 用户下拉菜单
const userMenuOpen = ref(false);

// 监控面板
const monitorDays = ref(7);
const monitorLoading = reactive({ my: false, admin: false });
const costChartEl = ref(null);
const modelPieEl = ref(null);
let costChart = null;
let modelPieChart = null;
const monitorData = reactive({
  myCost: null,
  todayCost: null,
  errorRate: null,
  errorPct: null,
  modelReport: [],
  userReport: []
});
const adminUsers = reactive({ items: [], total: 0, page: 0, size: 20, totalPages: 0 });
const adminUserPage = ref(0);

const toasts = ref([]);
const dialog = reactive({
  visible: false,
  type: 'confirm',
  title: '',
  message: '',
  variant: 'default',
  confirmText: '确定',
  cancelText: '取消',
  inputId: 'app-dialog-input',
  inputLabel: '',
  inputValue: '',
  placeholder: '',
  fields: [],
  formValues: {},
  choices: [],
  choiceValue: '',
  resolver: null
});

const isAdmin = computed(() => user.value?.roles?.includes('ROLE_ADMIN') ?? false);

// 当前选中的组织对象
const currentOrg = computed(() => organizations.value.find(o => o.orgId === currentOrgId.value) ?? null);
// 当前组织的展示名称
const currentOrgName = computed(() => {
  const org = currentOrg.value;
  if (!org) return '个人空间';
  return org.orgType === 'PERSONAL' ? '个人空间' : (org.name || org.orgId);
});

const uploadQueueFinished = computed(() =>
  uploadQueue.value.length > 0 &&
  uploadQueue.value.every(t => t.status === 'done' || t.status === 'error')
);
const uploadQueueSummary = computed(() => {
  const q = uploadQueue.value;
  if (!q.length) return '';
  const done = q.filter(t => t.status === 'done').length;
  const err  = q.filter(t => t.status === 'error').length;
  const active = q.filter(t => t.status === 'uploading' || t.status === 'processing').length;
  if (active) return `正在上传 ${q.length} 个文件（${done} 完成）`;
  if (err) return `已完成 ${done}，失败 ${err}`;
  return `${done} 个文件上传完成`;
});
const currentSessionTitle = computed(() => sessions.value.find(s => s.id === sessionId.value)?.title || '新对话');
const modelOptions = [
  { value: 'deepseek', label: 'DeepSeek（默认）', desc: '快速、稳定，适合日常对话' },
  { value: 'claude', label: 'Claude', desc: '适合长文本与复杂分析' }
];
const currentModelLabel = computed(() => modelOptions.find(option => option.value === model.value)?.label || '选择模型');
const currentSessionSending = computed(() => ensureSessionRuntime(sessionId.value).sending);
const currentKbName = computed(() => {
  if (!currentKbId.value) return '';
  return knowledgeBases.value.find(kb => kb.id === currentKbId.value)?.name || '知识库';
});
const filteredSessions = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase();
  if (!keyword) return sessions.value;
  return sessions.value.filter(session => {
    if (session.title.toLowerCase().includes(keyword)) return true;
    return (sessionMessages[session.id] || []).some(message =>
      stripHtml(message.html).toLowerCase().includes(keyword)
    );
  });
});

onMounted(async () => {
  if (!api.getToken()) {
    location.replace('/login.html');
    return;
  }

  // ── 加载历史会话：优先从服务端，降级到 localStorage ──
  let serverLoaded = false;
  try {
    serverLoaded = await loadSessionsFromServer();
  } catch (e) {
    console.warn('服务端历史加载失败，降级 localStorage:', e.message);
  }
  if (!serverLoaded) {
    const localRestored = loadSessions();
    if (!localRestored) {
      addSession(sessionId.value, '新对话');
    } else {
      // 将 localStorage 数据异步同步到服务端（静默，不影响用户）
      try { await syncLocalToServer(); } catch { /* 忽略 */ }
    }
  }

  // 监听会话列表和 sessionId 变化，自动持久化到 localStorage（作为离线备份）
  watch(sessions, scheduleSave, { deep: true });
  watch(sessionId, scheduleSave);

  // 必须先加载组织（确保 currentOrgId 赋值完毕），再加载知识库
  await loadOrganizations();
  await loadKnowledgeBases();

  // 点击页面其他地方时关闭用户下拉菜单
  document.addEventListener('click', () => { userMenuOpen.value = false; });
});

// 组件卸载时销毁图表实例
onUnmounted(() => {
  costChart?.destroy();
  modelPieChart?.destroy();
});

function generateId() {
  return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
}

/**
 * 从服务端加载会话列表和最近会话的消息
 * @returns {boolean} 是否成功加载到数据
 */
async function loadSessionsFromServer() {
  const serverSessions = await api.listChatSessions();
  if (!serverSessions?.length) return false;

  // 恢复会话列表
  sessions.value = serverSessions.map(s => ({
    id: s.sessionId,
    title: s.title || '新对话',
    createdAt: s.createdAt ? new Date(s.createdAt).getTime() : Date.now()
  }));

  // 加载最近一个会话的消息（其余会话点击时懒加载）
  const firstSession = sessions.value[0];
  if (firstSession) {
    try {
      const msgs = await api.getChatMessages(firstSession.id);
      sessionMessages[firstSession.id] = msgs.map(m => ({
        id: generateId(),
        role: m.role,
        html: m.role === 'user' ? formatMarkdown(m.content) : formatMarkdown(m.content)
      }));
    } catch { /* 忽略单条加载失败 */ }
  }

  // 恢复激活的会话（取最近一个）
  sessionId.value = firstSession?.id || generateId();
  messages.value = sessionMessages[sessionId.value] || [];
  if (!sessionMessages[sessionId.value]) {
    sessionMessages[sessionId.value] = [];
    messages.value = sessionMessages[sessionId.value];
  }
  return true;
}

/**
 * 切换会话时懒加载该会话的历史消息（如果还没有缓存）
 */
async function lazyLoadSessionMessages(id) {
  if (sessionMessages[id]?.length) return; // 已有缓存，跳过
  try {
    const msgs = await api.getChatMessages(id);
    sessionMessages[id] = msgs.map(m => ({
      id: generateId(),
      role: m.role,
      html: formatMarkdown(m.content)
    }));
  } catch { sessionMessages[id] = []; }
}

/**
 * 将 localStorage 中的历史数据同步到服务端（静默迁移）
 */
async function syncLocalToServer() {
  const toSync = sessions.value.map(s => ({
    id: s.id,
    title: s.title,
    messages: (sessionMessages[s.id] || []).map(m => ({
      role: m.role,
      content: stripHtml(m.html)
    }))
  })).filter(s => s.messages.length > 0);
  if (toSync.length) await api.syncChatSessions(toSync);
}

async function handleLogout() {
  userMenuOpen.value = false;
  const confirmed = await showConfirm({
    title: '退出登录',
    message: '确认退出当前账号吗？',
    confirmText: '退出登录',
    variant: 'danger'
  });
  if (confirmed) api.logout();
}

async function handleChangePassword() {
  userMenuOpen.value = false;
  const form = await showForm({
    title: '修改密码',
    confirmText: '确认修改',
    fields: [
      { key: 'oldPassword', label: '当前密码', placeholder: '请输入当前密码', type: 'password' },
      { key: 'newPassword', label: '新密码', placeholder: '至少 6 位', type: 'password' },
      { key: 'confirmPassword', label: '确认新密码', placeholder: '再次输入新密码', type: 'password' }
    ]
  });
  if (!form) return;
  if (!form.oldPassword || !form.newPassword) return showToast('warning', '密码不能为空');
  if (form.newPassword !== form.confirmPassword) return showToast('error', '两次密码不一致');
  try {
    await api.changePassword(form.oldPassword, form.newPassword);
    showToast('success', '密码修改成功，请重新登录');
    setTimeout(() => api.logout(), 1500);
  } catch (error) {
    showToast('error', `修改失败：${error.message}`);
  }
}

// ── 监控数据加载 ─────────────────────────────────────────────────

async function loadMyUsage() {
  monitorLoading.my = true;
  try {
    const data = await api.getMyTodayCost();
    monitorData.myCost = Number(data.costUsd ?? 0).toFixed(6);
  } catch {
    monitorData.myCost = 'N/A';
  } finally {
    monitorLoading.my = false;
  }
}

async function loadAdminStats() {
  if (!isAdmin.value) return;
  monitorLoading.admin = true;
  try {
    const [costData, errorData, modelReport, userReport] = await Promise.all([
      api.adminGetTodayCost(),
      api.adminGetErrorRate(5),
      api.adminGetModelReport(monitorDays.value),
      api.adminGetUserReport(monitorDays.value)
    ]);
    monitorData.todayCost = Number(costData.costUsd ?? 0).toFixed(6);
    monitorData.errorRate = errorData.errorRate;
    monitorData.errorPct = errorData.errorPct;
    monitorData.modelReport = modelReport;
    monitorData.userReport = userReport;
    // 数据加载完成后渲染图表
    await nextTick();
    renderMonitorCharts();
  } catch (e) {
    showToast('error', `加载监控数据失败：${e.message}`);
  } finally {
    monitorLoading.admin = false;
  }
}

/** 渲染监控图表 */
function renderMonitorCharts() {
  renderCostTrendChart();
  renderModelPieChart();
}

function renderCostTrendChart() {
  if (!costChartEl.value) return;
  const report = monitorData.modelReport || [];
  if (!report.length) return;

  // 生成近 N 天的日期标签（简化：按模型聚合的数据没有日期维度，用模型名作 x 轴）
  const labels = report.map(r => r.modelName || '未知');
  const costs  = report.map(r => parseFloat(r.costUsd || 0));

  if (costChart) costChart.destroy();
  costChart = new Chart(costChartEl.value, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: '费用（USD）',
        data: costs,
        backgroundColor: 'rgba(77,107,254,0.7)',
        borderColor: 'rgba(77,107,254,1)',
        borderWidth: 1,
        borderRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, ticks: { callback: v => '$' + v.toFixed(4) } }
      }
    }
  });
}

function renderModelPieChart() {
  if (!modelPieEl.value) return;
  const report = monitorData.modelReport || [];
  if (!report.length) return;

  const labels = report.map(r => r.modelName || '未知');
  const costs  = report.map(r => parseFloat(r.costUsd || 0));
  const COLORS = ['#4D6BFE','#00A96E','#D69E2E','#E53E3E','#9B59B6','#1ABC9C'];

  if (modelPieChart) modelPieChart.destroy();
  modelPieChart = new Chart(modelPieEl.value, {
    type: 'doughnut',
    data: {
      labels,
      datasets: [{
        data: costs,
        backgroundColor: COLORS.slice(0, labels.length),
        borderWidth: 2,
        borderColor: '#fff'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
        tooltip: { callbacks: { label: ctx => ` $${parseFloat(ctx.raw).toFixed(6)}` } }
      }
    }
  });
}

async function loadAdminUsers() {
  if (!isAdmin.value) return;
  try {
    const data = await api.adminListUsers(adminUserPage.value, 20);
    Object.assign(adminUsers, data);
  } catch (e) {
    showToast('error', `加载用户列表失败：${e.message}`);
  }
}

async function handleDisableUser(userId) {
  const confirmed = await showConfirm({
    title: '禁用用户',
    message: `确认禁用用户 ${userId}？禁用后该用户无法登录。`,
    confirmText: '禁用',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.adminDisableUser(userId);
    showToast('success', `已禁用 ${userId}`);
    await loadAdminUsers();
  } catch (e) {
    showToast('error', `操作失败：${e.message}`);
  }
}

async function handleEnableUser(userId) {
  try {
    await api.adminEnableUser(userId);
    showToast('success', `已启用 ${userId}`);
    await loadAdminUsers();
  } catch (e) {
    showToast('error', `操作失败：${e.message}`);
  }
}

// 监控 Tab 激活时自动加载数据
function onMonitorTabActivate() {
  loadMyUsage();
  if (isAdmin.value) {
    loadAdminStats();
    loadAdminUsers();
  }
}

function fmtNum(val) {
  if (val == null) return '—';
  return Number(val).toLocaleString();
}

function fmtCost(val) {
  if (val == null) return '—';
  return Number(val).toFixed(6);
}

function switchTab(key) {
  activeTab.value = key;
  userMenuOpen.value = false;
  if (key === 'monitor') onMonitorTabActivate();
}

function selectModel(value) {
  model.value = value;
  modelMenuOpen.value = false;
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  modelMenuOpen.value = false;
}

function openSearch() {
  searchVisible.value = true;
  searchQuery.value = '';
  nextTick(() => searchInputEl.value?.focus());
}

function closeSearch() {
  searchVisible.value = false;
}

function openSearchResult(id) {
  switchSession(id);
  closeSearch();
}

function searchResultSnippet(session) {
  const keyword = searchQuery.value.trim().toLowerCase();
  const storedMessages = sessionMessages[session.id] || [];
  if (keyword) {
    const matched = storedMessages.find(message => stripHtml(message.html).toLowerCase().includes(keyword));
    if (matched) return makeSearchSnippet(stripHtml(matched.html), keyword);
  }
  if (storedMessages.length) {
    const last = storedMessages[storedMessages.length - 1];
    return stripHtml(last.html).slice(0, 80) || '当前对话';
  }
  return '点击打开这段历史对话';
}

function formatSessionDate(value) {
  const date = new Date(value || Date.now());
  const today = new Date();
  if (date.toDateString() === today.toDateString()) return '今天';
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function newSession() {
  const id = generateId();
  addSession(id, '新对话');
  sessionId.value = id;
  setCurrentMessages(sessionMessages[id]);
  activeTab.value = 'chat';
}

function addSession(id, title) {
  if (!sessions.value.some(s => s.id === id)) {
    sessions.value.unshift({ id, title, createdAt: Date.now() });
  }
  if (!sessionMessages[id]) sessionMessages[id] = [];
}

async function switchSession(id) {
  if (!sessionMessages[id]) sessionMessages[id] = [];
  sessionId.value = id;
  setCurrentMessages(sessionMessages[id]);
  activeTab.value = 'chat';
  // 懒加载历史消息（若本地无缓存则从服务端拉取）
  await lazyLoadSessionMessages(id);
  setCurrentMessages(sessionMessages[id]);
}

async function removeSession(id) {
  const index = sessions.value.findIndex(s => s.id === id);
  if (index < 0) return;
  const target = sessions.value[index];
  const confirmed = await showConfirm({
    title: '删除会话',
    message: `确认删除会话「${target.title}」？\n删除后不会影响其他会话。`,
    confirmText: '删除',
    variant: 'danger'
  });
  if (!confirmed) return;
  const [removed] = sessions.value.splice(index, 1);
  stopSessionGeneration(id, false);
  delete sessionMessages[id];
  delete sessionRuntime[id];
  // 同步删除服务端记录（静默，不影响 UI）
  api.deleteChatSession(id).catch(() => {});
  showToast('info', `已删除会话：${removed.title}`);
  if (sessionId.value === id) {
    if (sessions.value.length) switchSession(sessions.value[0].id);
    else newSession();
  }
}

function updateSessionTitle(text, id = sessionId.value) {
  const session = sessions.value.find(s => s.id === id);
  if (session && session.title === '新对话') {
    session.title = text.slice(0, 12) + (text.length > 12 ? '...' : '');
  }
}

function setCurrentMessages(items) {
  sessionMessages[sessionId.value] = items || [];
  messages.value = sessionMessages[sessionId.value];
}

function setChatMode(mode) {
  reactEnabled.value = mode === 'expert';
  streamEnabled.value = true;
}

/** 点击附件按钮 → 弹出知识库选择对话框，关联后消息将基于该知识库回答 */
async function handleAttachKb() {
  if (knowledgeBases.value.length === 0) {
    showToast('warning', '暂无知识库，请先在「知识库」Tab 上传文档');
    return;
  }
  // 构造选项列表（最多展示 4 个，超出提示切换到知识库 Tab）
  const choices = knowledgeBases.value.slice(0, 4).map(kb => ({
    value: String(kb.id),
    label: kb.name,
    desc: `${kb.docCount || 0} 篇文档`
  }));
  // 若当前已关联，增加"取消关联"选项
  if (currentKbId.value) {
    choices.unshift({ value: '', label: '取消关联知识库', desc: '恢复为普通对话模式' });
  }
  const chosen = await showChoice({
    title: '关联知识库',
    message: '选择知识库后，本次对话将基于其内容生成答案（RAG 模式）',
    confirmText: '确认',
    choices,
    defaultValue: currentKbId.value ? String(currentKbId.value) : choices[0]?.value
  });
  if (chosen === false || chosen === undefined) return; // 取消
  if (chosen === '') {
    currentKbId.value = null;
    showToast('info', '已取消关联知识库');
  } else {
    currentKbId.value = Number(chosen);
    const kb = knowledgeBases.value.find(k => k.id === currentKbId.value);
    showToast('success', `已关联知识库「${kb?.name || chosen}」，发送消息将基于知识库内容回答`);
  }
}

async function sendQuick(text) {
  messageInput.value = text;
  await sendMessage();
}

async function sendMessage() {
  const text = messageInput.value.trim();
  const requestSessionId = sessionId.value;
  const runtime = ensureSessionRuntime(requestSessionId);
  if (!text || runtime.sending) return;
  messageInput.value = '';
  resetInputHeight();
  pushMessage(requestSessionId, 'user', formatMarkdown(text));
  updateSessionTitle(text, requestSessionId);

  if (reactEnabled.value) await doReactChat(requestSessionId, text);
  else if (streamEnabled.value) await doStreamChat(requestSessionId, text);
  else await doSyncChat(requestSessionId, text);
}

async function doSyncChat(requestSessionId, text) {
  const runtime = ensureSessionRuntime(requestSessionId);
  runtime.sending = true;
  runtime.cancelled = false;
  const requestId = ++runtime.requestId;
  const bubble = pushMessage(requestSessionId, 'ai', '<span class="typing-dots">●●●</span>');
  runtime.bubble = bubble;
  try {
    const data = await api.chatSync(requestSessionId, text, currentKbId.value);
    if (runtime.requestId !== requestId || runtime.cancelled) return;
    bubble.html = formatMarkdown(data.reply);
  } catch (error) {
    if (runtime.requestId !== requestId || runtime.cancelled) return;
    bubble.html = `<span class="error-msg">请求失败：${escapeHtml(error.message)}</span>`;
    showToast('error', '发送失败，请检查网络或服务');
  } finally {
    if (runtime.requestId === requestId) {
      runtime.sending = false;
      runtime.bubble = null;
      scrollSessionToBottom(requestSessionId);
      scheduleSave();
    }
  }
}

async function doStreamChat(requestSessionId, text) {
  const runtime = ensureSessionRuntime(requestSessionId);
  runtime.sending = true;
  runtime.cancelled = false;
  const requestId = ++runtime.requestId;
  // 初始显示“思考中” loading，等待 RAG 检索 + LLM 首个 token
  const bubble = pushMessage(requestSessionId, 'ai', '<span class="typing-dots">●●●</span>');
  let fullText = '';
  let firstToken = true;
  const eventSource = api.chatStream(requestSessionId, text, currentKbId.value);
  runtime.eventSource = eventSource;
  runtime.bubble = bubble;
  runtime.text = '';

  // 节流渲染：token 到来时仅标记待更新，每 40ms 刷新一次 DOM
  // 避免模型快速连续输出时 Vue 批量合并导致的“一次性输出”视觉问题
  let renderPending = false;
  let renderRaf = null;
  function scheduleRender() {
    if (renderPending) return;
    renderPending = true;
    renderRaf = requestAnimationFrame(() => {
      renderPending = false;
      if (runtime.requestId !== requestId || runtime.cancelled) return;
      bubble.html = formatMarkdown(fullText) + '<span class="typing-cursor"></span>';
      scrollSessionToBottom(requestSessionId);
    });
  }

  eventSource.onmessage = (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    if (event.data === '[DONE]') return;
    // 收到第一个 token 时清除 loading 动画，正式开始流式输出
    if (firstToken) {
      firstToken = false;
      fullText = '';
    }
    fullText += event.data;
    runtime.text = fullText;
    scheduleRender();
  };
  eventSource.addEventListener('replace', (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    firstToken = false;
    fullText = event.data;
    runtime.text = fullText;
    scheduleRender();
  });
  eventSource.addEventListener('done', () => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    eventSource.close();
    cancelAnimationFrame(renderRaf);
    renderPending = false;
    bubble.html = formatMarkdown(fullText);
    finish();
  });
  eventSource.onerror = () => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    eventSource.close();
    cancelAnimationFrame(renderRaf);
    renderPending = false;
    if (!fullText) {
      bubble.html = '<span class="error-msg">连接失败，请重试</span>';
      showToast('error', '流式连接失败');
    } else {
      bubble.html = formatMarkdown(fullText);
    }
    finish();
  };

  function finish() {
    if (runtime.requestId === requestId) {
      runtime.sending = false;
      runtime.eventSource = null;
      runtime.bubble = null;
      runtime.text = '';
      scrollSessionToBottom(requestSessionId);
      scheduleSave();
    }
  }
}

function stopGeneration() {
  stopSessionGeneration(sessionId.value, true);
}

async function doReactChat(requestSessionId, text) {
  const runtime = ensureSessionRuntime(requestSessionId);
  runtime.sending = true;
  runtime.cancelled = false;
  const requestId = ++runtime.requestId;
  const bubble = pushMessage(requestSessionId, 'ai', '<div class="react-thinking"><span class="typing-dots">●●●</span><span class="react-thinking-label">思考中…</span></div>');
  runtime.bubble = bubble;
  runtime.reactSteps = [];
  runtime.reactAnswer = null;
  runtime.reactStartMs = Date.now();

  const eventSource = api.chatReactStream(requestSessionId, text, currentKbId.value);
  runtime.eventSource = eventSource;

  // 收到每个推理步骤后实时更新气泡
  eventSource.addEventListener('step', (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    try {
      const step = JSON.parse(event.data);
      runtime.reactSteps.push(step);
      bubble.html = renderReactStreamBubble(runtime.reactSteps, null, Date.now() - runtime.reactStartMs);
      scrollSessionToBottom(requestSessionId);
    } catch { /* JSON parse error, ignore */ }
  });

  // 收到最终答案
  eventSource.addEventListener('answer', (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    try {
      const data = JSON.parse(event.data);
      runtime.reactAnswer = data.answer;
      bubble.html = renderReactStreamBubble(runtime.reactSteps, data.answer, data.durationMs);
      scrollSessionToBottom(requestSessionId);
    } catch { /* ignore */ }
  });

  // 输出脱敏替换最终答案
  eventSource.addEventListener('replace-answer', (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    try {
      const data = JSON.parse(event.data);
      runtime.reactAnswer = data.answer;
      bubble.html = renderReactStreamBubble(runtime.reactSteps, data.answer, Date.now() - runtime.reactStartMs);
      scrollSessionToBottom(requestSessionId);
    } catch { /* ignore */ }
  });

  // 推理完成
  eventSource.addEventListener('done', () => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    eventSource.close();
    finishReact();
  });

  // 错误
  eventSource.addEventListener('error', (event) => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    eventSource.close();
    const msg = event.data || '推理失败，请重试';
    if (!runtime.reactAnswer) {
      bubble.html = `<span class="error-msg">推理失败：${escapeHtml(msg)}</span>`;
      showToast('error', '深度推理失败，请重试');
    }
    finishReact();
  });

  eventSource.onerror = () => {
    if (runtime.eventSource !== eventSource || runtime.requestId !== requestId || runtime.cancelled) return;
    eventSource.close();
    if (!runtime.reactAnswer && !runtime.reactSteps?.length) {
      bubble.html = '<span class="error-msg">连接失败，请重试</span>';
      showToast('error', '深度推理连接失败');
    }
    finishReact();
  };

  function finishReact() {
    if (runtime.requestId === requestId) {
      runtime.sending = false;
      runtime.eventSource = null;
      runtime.bubble = null;
      runtime.reactSteps = null;
      runtime.reactAnswer = null;
      scrollSessionToBottom(requestSessionId);
      scheduleSave();
    }
  }
}

/**
 * 渲染 ReAct 流式推理气泡
 * steps: 已完成的推理步骤数组
 * answer: 最终答案（null 时显示"思考中"）
 * durationMs: 耗时毫秒
 */
function renderReactStreamBubble(steps, answer, durationMs) {
  const seconds = durationMs ? Math.max(1, Math.round(durationMs / 1000)) : '';
  const stepsHtml = (steps || []).map(step => `
    <div class="react-step">
      <div class="react-step-label">第 ${step.iteration} 步${step.toolName ? ` · ${escapeHtml(step.toolName)}` : ''}</div>
      ${step.thought ? `<div class="react-thought"><span>思考摘要</span>${escapeHtml(trimText(step.thought, 220))}</div>` : ''}
      ${step.toolName ? `<div class="react-tool"><span>工具调用</span>${escapeHtml(step.toolName || '')}(${escapeHtml(step.toolArgs || '')})</div>` : ''}
      ${step.observation ? `<div class="react-obs"><span>观察结果</span>${escapeHtml(trimText(step.observation, 260))}</div>` : ''}
    </div>
  `).join('');

  const summaryLabel = answer
    ? `已思考${seconds ? `（用时 ${seconds} 秒）` : ''}`
    : `思考中${steps?.length ? `（已完成 ${steps.length} 步）` : ''}…`;

  const detailsAttr = answer ? '' : ' open';
  const stepsBlock = (steps?.length || !answer) ? `
    <details class="react-steps-container"${detailsAttr}>
      <summary class="react-steps-summary">
        <span class="react-steps-title">${summaryLabel}</span>
        ${steps?.length ? `<span class="react-steps-count">${steps.length} 步</span>` : ''}
      </summary>
      <div class="react-steps">
        ${stepsHtml}
        ${!answer ? '<div class="react-step react-step-pending"><span class="typing-dots">●●●</span></div>' : ''}
      </div>
    </details>
  ` : '';

  const answerBlock = answer
    ? `<div class="react-answer">${formatMarkdown(answer)}</div>`
    : '';

  return stepsBlock + answerBlock;
}

function renderReactAnswer(data) {
  if (!data.steps?.length) return formatMarkdown(data.answer);
  const steps = data.steps.map(step => `
    <div class="react-step">
      <div class="react-step-label">第 ${step.iteration} 步${step.toolName ? ` · ${escapeHtml(step.toolName)}` : ''}</div>
      ${step.thought ? `<div class="react-thought"><span>思考摘要</span>${escapeHtml(trimText(step.thought, 220))}</div>` : ''}
      ${step.toolName ? `<div class="react-tool"><span>工具调用</span>${escapeHtml(step.toolName || '')}(${escapeHtml(step.toolArgs || '')})</div>` : ''}
      ${step.observation ? `<div class="react-obs"><span>观察结果</span>${escapeHtml(trimText(step.observation, 260))}</div>` : ''}
    </div>
  `).join('');
  const seconds = data.durationMs ? Math.max(1, Math.round(data.durationMs / 1000)) : '';
  return `
    <details class="react-steps-container" open>
      <summary class="react-steps-summary">
        <span class="react-steps-title">已思考${seconds ? `（用时 ${seconds} 秒）` : ''}</span>
        <span class="react-steps-count">${data.iterations || data.steps.length} 步</span>
      </summary>
      <div class="react-steps">${steps}</div>
    </details>
    <div class="react-answer">${formatMarkdown(data.answer)}</div>
  `;
}

function pushMessage(targetSessionId, role, html) {
  const item = { id: generateId(), role, html };
  if (!sessionMessages[targetSessionId]) sessionMessages[targetSessionId] = [];
  sessionMessages[targetSessionId].push(item);
  if (targetSessionId === sessionId.value && messages.value !== sessionMessages[targetSessionId]) {
    messages.value = sessionMessages[targetSessionId];
  }
  scrollSessionToBottom(targetSessionId);
  // 消息新增时触发持久化
  scheduleSave();
  return item;
}

async function handleClearMemory() {
  const confirmed = await showConfirm({
    title: '清除记忆',
    message: `确认清除当前会话的所有记忆？\n会话ID: ${sessionId.value}`,
    confirmText: '清除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    stopSessionGeneration(sessionId.value, false);
    await api.clearMemory(sessionId.value);
    setCurrentMessages([]);
    showToast('success', '记忆已清除，对话重新开始');
  } catch {
    showToast('error', '清除失败，请重试');
  }
}

function toggleReact() {
  reactEnabled.value = !reactEnabled.value;
  if (reactEnabled.value) showToast('info', '深度推理已开启，适合复杂多步任务');
}

/**
 * 输入框键盘事件
 * - enterToSend=true：Enter 发送，Shift+Enter 换行
 * - enterToSend=false：Ctrl+Enter 发送，Enter 换行（默认）
 */
function handleInputKeydown(event) {
  if (event.key === 'Enter') {
    if (enterToSend.value) {
      // Enter 发送模式：Shift+Enter 换行，单独 Enter 发送
      if (event.shiftKey) return; // 让默认行为换行
      event.preventDefault();
      sendMessage();
    } else {
      // Ctrl+Enter 发送模式：Ctrl+Enter 发送，其他 Enter 换行
      if (event.ctrlKey || event.metaKey) {
        event.preventDefault();
        sendMessage();
      }
      // 其他 Enter 走默认行为（换行）
    }
  }
}

function autoResize() {
  const el = messageInputEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

function resetInputHeight() {
  nextTick(() => {
    if (messageInputEl.value) messageInputEl.value.style.height = 'auto';
  });
}

function ensureSessionRuntime(id) {
  if (!sessionRuntime[id]) {
    sessionRuntime[id] = {
      sending: false,
      eventSource: null,
      bubble: null,
      text: '',
      requestId: 0,
      cancelled: false,
      // ReAct 流式专用字段
      reactSteps: null,
      reactAnswer: null,
      reactStartMs: 0
    };
  }
  return sessionRuntime[id];
}

function stopSessionGeneration(id, showNotice = true) {
  const runtime = ensureSessionRuntime(id);
  if (!runtime.sending && !runtime.eventSource) return;
  runtime.cancelled = true;
  runtime.requestId += 1;
  runtime.eventSource?.close();
  if (runtime.bubble) {
    let html = '';
    if (runtime.reactSteps !== null && runtime.reactSteps !== undefined) {
      // ReAct 流式模式：保留已推送的步骤内容
      html = renderReactStreamBubble(
        runtime.reactSteps,
        runtime.reactAnswer || null,
        Date.now() - (runtime.reactStartMs || Date.now())
      );
    } else {
      // 快速/同步模式：保留已累积的文本
      html = runtime.text ? formatMarkdown(runtime.text) : '';
    }
    runtime.bubble.html = `${html}<div class="stopped-msg">已停止生成</div>`;
  }
  runtime.sending = false;
  runtime.eventSource = null;
  runtime.bubble = null;
  runtime.text = '';
  runtime.reactSteps = null;
  runtime.reactAnswer = null;
  scrollSessionToBottom(id);
  if (showNotice) showToast('info', '已停止当前会话的生成');
}

function scrollSessionToBottom(targetSessionId) {
  if (targetSessionId === sessionId.value) scrollToBottom();
}

function scrollToBottom() {
  nextTick(() => {
    if (chatMessagesEl.value) chatMessagesEl.value.scrollTop = chatMessagesEl.value.scrollHeight;
  });
}

async function loadKnowledgeBases() {
  try {
    // 传当前组织 ID，切换组织后知识库列表会随之变化
    knowledgeBases.value = await api.listKnowledgeBases(currentOrgId.value || undefined);
    // 如果当前选中的知识库不在新列表中，清空选择
    if (currentKbId.value && !knowledgeBases.value.find(kb => kb.id === currentKbId.value)) {
      currentKbId.value = null;
      docs.value = [];
    }
    if (knowledgeBases.value.length && !currentKbId.value) selectKb(knowledgeBases.value[0].id);
  } catch {
    knowledgeBases.value = [];
  }
}

async function selectKb(kbId) {
  currentKbId.value = kbId;
  kbMembersVisible.value = false;
  await loadDocumentList();
}

async function handleCreateKb() {
  const name = await showPrompt({
    title: '新建知识库',
    inputLabel: '知识库名称',
    placeholder: '例如：产品文档、客户案例、内部 SOP',
    confirmText: '创建'
  });
  if (!name?.trim()) return;
  try {
    await api.createKnowledgeBase(name.trim(), '', currentOrgId.value || undefined);
    showToast('success', `知识库「${name.trim()}」已创建到「${currentOrgName.value}」`);
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `创建失败：${error.message}`);
  }
}

async function handleDeleteKb(kbId) {
  const kb = knowledgeBases.value.find(item => item.id === kbId);
  if (!kb) return;
  const confirmed = await showConfirm({
    title: '删除知识库',
    message: `确认删除知识库「${kb.name}」？\n\n此操作不可恢复，所有文档和切片将被永久删除。`,
    confirmText: '删除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.deleteKnowledgeBase(kbId, currentOrgId.value || undefined);
    showToast('success', `已删除：${kb.name}`);
    if (currentKbId.value === kbId) currentKbId.value = null;
    docs.value = [];
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `删除失败：${error.message}`);
  }
}

async function loadDocumentList() {
  if (!currentKbId.value) {
    docs.value = [];
    return;
  }
  try {
    const data = await api.listDocuments(currentKbId.value, currentOrgId.value || undefined);
    docs.value = data.map(doc => ({
      id: doc.id,
      filename: doc.name ?? doc.filename,
      chunks: doc.chunkCount ?? doc.chunks ?? 0,
      size: doc.fileSize ?? 0,
      status: doc.parseStatus ?? 'UNKNOWN',
      uploadedAt: doc.createdAt ?? new Date().toLocaleString()
    }));
  } catch (err) {
    docs.value = [];
    // 仅在非首次加载时提示错误，避免启动时误报
    if (currentKbId.value) {
      showToast('error', `加载文档列表失败：${err?.message || '未知错误'}`);
    }
  }
}

function triggerUpload() {
  if (!currentKbId.value) {
    showToast('warning', '请先选择或创建知识库');
    return;
  }
  fileInputEl.value?.click();
}

function handleFileChange(event) {
  Array.from(event.target.files || []).forEach(handleUpload);
  event.target.value = '';
}

function handleDrop(event) {
  dragOver.value = false;
  if (!currentKbId.value) {
    showToast('warning', '请先选择或创建知识库');
    return;
  }
  Array.from(event.dataTransfer.files || []).forEach(handleUpload);
}

async function handleUpload(file) {
  const allowed = ['pdf', 'doc', 'docx', 'txt', 'md'];
  const ext = file.name.split('.').pop().toLowerCase();
  if (!allowed.includes(ext)) return showToast('error', `不支持的文件类型：.${ext}`);
  if (file.size > 50 * 1024 * 1024) return showToast('error', `文件过大（最大 50MB）：${file.name}`);

  // 创建任务条目
  const task = reactive({
    id: Date.now() + '-' + Math.random().toString(36).slice(2, 6),
    filename: file.name,
    status: 'uploading',  // uploading | processing | done | error | pending
    pct: 0,
    barWidth: 0,
    loaded: 0,
    total: file.size,
    startMs: Date.now(),
    speedText: '',
    etaText: '',
    loadedText: formatFileSize(file.size),
    totalText: formatFileSize(file.size),
    error: ''
  });
  uploadQueue.value.push(task);

  try {
    const data = await api.uploadDocument(currentKbId.value, file, ({ loaded, total, pct }) => {
      const elapsed = (Date.now() - task.startMs) / 1000;
      const speed = elapsed > 0 ? loaded / elapsed : 0;
      const remaining = speed > 0 && total ? (total - loaded) / speed : 0;

      task.loaded = loaded;
      task.total = total || file.size;
      task.pct = pct;
      task.barWidth = pct;
      task.loadedText = formatFileSize(loaded);
      task.totalText = formatFileSize(task.total);
      task.speedText = speed > 0 ? `${formatFileSize(speed)}/s` : '';
      task.etaText = remaining > 1 ? fmtEta(remaining) : '';

      // 接近 100% 后进入"解析中"状态
      if (pct >= 100) {
        task.status = 'processing';
        task.barWidth = 100;
      }
    }, currentOrgId.value || undefined);

    task.status = 'done';
    task.pct = 100;
    task.barWidth = 100;

    // 异步解析：服务端立即返回 documentId，状态为 PROCESSING
    docs.value.push({
      id: data.documentId ?? Date.now().toString(),
      filename: file.name,
      chunks: data.chunkCount ?? 0,
      size: file.size,
      status: data.status === 'PROCESSING' ? 'PROCESSING' : 'DONE',
      uploadedAt: new Date().toLocaleString()
    });
    if (data.status === 'PROCESSING') {
      showToast('info', `${file.name} 已上传，正在后台解析...`);
      // 开始轮询该文档的解析状态
      pollDocumentStatus(data.documentId);
    } else {
      showToast('success', `${file.name} 导入成功`);
    }
    await loadKnowledgeBases();

    // 全部完成后 3 秒清空队列
    if (uploadQueueFinished.value) {
      setTimeout(() => {
        if (uploadQueueFinished.value) uploadQueue.value = [];
      }, 3000);
    }
  } catch (error) {
    task.status = 'error';
    task.barWidth = task.pct;
    task.error = error.message || '上传失败';
    showToast('error', `上传失败：${file.name}`);
  }
}

function fmtEta(seconds) {
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return `${m}m${s}s`;
}

/**
 * 轮询文档解析状态，直到 DONE 或 FAILED
 * @param {string|number} documentId 要轮询的文档 ID
 */
function pollDocumentStatus(documentId) {
  if (!documentId || !currentKbId.value) return;
  const MAX_POLLS = 60; // 最多轮询 60 次（约 2 分钟）
  let count = 0;
  const timer = setInterval(async () => {
    count++;
    if (count > MAX_POLLS) {
      clearInterval(timer);
      return;
    }
    try {
      const data = await api.listDocuments(currentKbId.value, currentOrgId.value || undefined);
      const docItem = data.find(d => String(d.id) === String(documentId));
      if (!docItem) { clearInterval(timer); return; }

      const status = docItem.parseStatus ?? 'UNKNOWN';
      // 同步更新本地列表
      const localDoc = docs.value.find(d => String(d.id) === String(documentId));
      if (localDoc) {
        localDoc.status = status;
        localDoc.chunks = docItem.chunkCount ?? localDoc.chunks;
      }

      if (status === 'DONE') {
        clearInterval(timer);
        const name = localDoc?.filename || docItem.name || '文档';
        showToast('success', `「${name}」解析完成，共 ${docItem.chunkCount ?? 0} 个切片`);
        await loadKnowledgeBases();
      } else if (status === 'FAILED') {
        clearInterval(timer);
        showToast('error', `文档解析失败：${docItem.parseError || '未知错误'}`);
      }
    } catch { /* 轮询静默忽略错误 */ }
  }, 2000); // 每 2 秒轮询一次
}

async function handleDeleteDoc(doc) {
  const confirmed = await showConfirm({
    title: '删除文档',
    message: `确认从知识库删除：${doc.filename}？\n\n此操作不可恢复。`,
    confirmText: '删除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.deleteDocument(currentKbId.value, doc.id, currentOrgId.value || undefined);
    docs.value = docs.value.filter(item => item.id !== doc.id);
    showToast('success', `已删除：${doc.filename}`);
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `删除失败：${error.message}`);
  }
}

async function openKbMembers() {
  kbMembersVisible.value = true;
  try {
    kbMembers.value = await api.listKbMembers(currentKbId.value, currentOrgId.value || undefined);
  } catch {
    kbMembers.value = [];
  }
}

function onKbMemberSearchInput() {
  clearTimeout(_kbSearchTimer);
  const keyword = kbMemberUsername.value;
  if (!keyword || keyword.length < 1) {
    kbMemberSuggestions.value = [];
    kbMemberSuggestionsVisible.value = false;
    kbMemberUserId.value = '';
    return;
  }
  _kbSearchTimer = setTimeout(async () => {
    try {
      kbMemberSuggestions.value = await api.searchUsers(keyword);
      kbMemberSuggestionsVisible.value = kbMemberSuggestions.value.length > 0;
    } catch { kbMemberSuggestions.value = []; }
  }, 300);
}

function selectKbMemberSuggestion(user) {
  kbMemberUserId.value = user.userId;
  kbMemberUsername.value = `${user.username}（${user.userId}）`;
  kbMemberSuggestions.value = [];
  kbMemberSuggestionsVisible.value = false;
}

function hideKbMemberSuggestions() {
  // 短暂延迟，让 mousedown 事件先触发
  setTimeout(() => { kbMemberSuggestionsVisible.value = false; }, 150);
}

async function addMemberToCurrentKb() {
  if (!kbMemberUserId.value) {
    // 若用户直接输入了用户名但没有从下拉选择，尝试精确匹配
    if (kbMemberUsername.value) {
      const results = await api.searchUsers(kbMemberUsername.value).catch(() => []);
      if (results.length === 1) {
        kbMemberUserId.value = results[0].userId;
      } else if (results.length > 1) {
        return showToast('warning', '匹配到多个用户，请从下拉列表中选择');
      } else {
        return showToast('warning', '未找到该用户，请确认用户名是否正确');
      }
    } else {
      return showToast('warning', '请输入用户名搜索并选择');
    }
  }
  try {
    await api.addKbMember(currentKbId.value, kbMemberUserId.value, kbMemberRole.value, currentOrgId.value || undefined);
    showToast('success', `已添加成员：${kbMemberUsername.value || kbMemberUserId.value}`);
    kbMemberUserId.value = '';
    kbMemberUsername.value = '';
    kbMemberSuggestions.value = [];
    await openKbMembers();
  } catch (error) {
    showToast('error', `添加成员失败：${error.message}`);
  }
}


async function loadOrganizations() {
  try {
    const memberships = await api.listOrganizations();
organizations.value = memberships.map(item => ({
                orgId: item.orgId,
                role: item.role,
                name: item.name,
                orgType: item.orgType
            }));
    if (organizations.value.length && !currentOrgId.value) currentOrgId.value = organizations.value[0].orgId;
  } catch {
    organizations.value = [];
  }
}

async function selectOrg(orgId) {
  if (currentOrgId.value === orgId) return;
  currentOrgId.value = orgId;
  // 切换组织后立即刷新知识库列表
  await loadKnowledgeBases();
  const org = organizations.value.find(o => o.orgId === orgId);
  const orgLabel = org?.orgType === 'PERSONAL' ? '个人空间' : (org?.name || orgId);
  showToast('info', `已切换到「${orgLabel}」`);
}

async function handleCreateOrg() {
  const form = await showForm({
    title: '创建企业组织',
    confirmText: '创建',
    fields: [
      { key: 'name', label: '组织名称', placeholder: '例如：星河科技' },
      { key: 'description', label: '组织描述（可选）', placeholder: '一句话说明这个组织的用途', multiline: true }
    ]
  });
  const name = form?.name;
  if (!name?.trim()) return;
  const description = form.description || '';
  try {
    await api.createOrganization(name.trim(), description.trim());
    showToast('success', `企业组织「${name.trim()}」创建成功`);
    await loadOrganizations();
  } catch (error) {
    showToast('error', `创建失败：${error.message}`);
  }
}

async function handleInviteMember() {
  if (!currentOrgId.value) return showToast('warning', '请先选择一个组织');
  const userId = await showPrompt({
    title: '邀请成员',
    inputLabel: '用户 ID',
    placeholder: '输入要邀请的用户 ID',
    confirmText: '下一步'
  });
  if (!userId?.trim()) return;
  const roleValue = await showChoice({
    title: '选择成员角色',
    message: `将邀请 ${userId.trim()} 加入当前组织。`,
    confirmText: '邀请',
    choices: [
      { value: 'MEMBER', label: '成员', desc: '可使用组织资源' },
      { value: 'ADMIN', label: '管理员', desc: '可邀请和管理成员' }
    ],
    defaultValue: 'MEMBER'
  });
  if (!roleValue) return;
  try {
    await api.inviteOrgMember(currentOrgId.value, userId.trim(), roleValue);
    showToast('success', `已邀请 ${userId.trim()} 加入组织`);
  } catch (error) {
    showToast('error', `邀请失败：${error.message}`);
  }
}

async function showOrgMembers(orgId) {
  try {
    const data = await api.getOrganization(orgId);
    orgModal.visible = true;
    orgModal.title = `组织成员 - ${orgId}`;
    orgModal.orgId = orgId;
    orgModal.members = data.members || [];
  } catch (error) {
    showToast('error', `加载成员失败：${error.message}`);
  }
}

async function removeOrgMemberFromModal(userId) {
  const member = orgModal.members.find(m => m.userId === userId);
  const displayName = member?.username || userId;
  const confirmed = await showConfirm({
    title: '移除成员',
    message: `确认移除成员「${displayName}」？`,
    confirmText: '移除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.removeOrgMember(orgModal.orgId, userId);
    showToast('success', `已移除：${displayName}`);
    await showOrgMembers(orgModal.orgId);
  } catch (error) {
    showToast('error', `移除失败：${error.message}`);
  }
}

/** 在成员弹窗中修改成员角色 */
async function changeOrgMemberRole(userId, newRole) {
  try {
    await api.updateOrgMemberRole(orgModal.orgId, userId, newRole);
    showToast('success', `角色已更新为「${orgRoleLabel(newRole)}」`);
    // 更新本地列表
    const m = orgModal.members.find(item => item.userId === userId);
    if (m) m.role = newRole;
  } catch (error) {
    showToast('error', `修改角色失败：${error.message}`);
    // 刷新以还原 UI
    await showOrgMembers(orgModal.orgId);
  }
}

/** 编辑知识库名称/描述 */
async function handleEditKb(kb) {
  const form = await showForm({
    title: '编辑知识库',
    confirmText: '保存',
    fields: [
      { key: 'name', label: '知识库名称', placeholder: '知识库名称', defaultValue: kb.name },
      { key: 'description', label: '描述（可选）', placeholder: '简短描述', multiline: true,
        defaultValue: kb.description || '' }
    ]
  });
  const name = form?.name;
  if (!name?.trim()) return;
  try {
    await api.updateKnowledgeBase(kb.id, name.trim(), form.description || '',
                                   currentOrgId.value || undefined);
    showToast('success', '知识库已更新');
    await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `更新失败：${error.message}`);
  }
}

/** 在 KB 成员面板中修改成员角色 */
async function changeKbMemberRole(userId, newRole) {
  try {
    await api.updateKbMemberRole(currentKbId.value, userId, newRole,
                                  currentOrgId.value || undefined);
    showToast('success', `角色已更新为「${kbRoleLabel(newRole)}」`);
    // 更新本地列表
    const m = kbMembers.value.find(item => item.userId === userId);
    if (m) m.role = newRole;
  } catch (error) {
    showToast('error', `修改角色失败：${error.message}`);
    await openKbMembers();
  }
}

/** 在 KB 成员面板中移除成员 */
async function removeKbMemberFromPanel(userId) {
  const member = kbMembers.value.find(m => m.userId === userId);
  const displayName = member?.username || userId;
  const confirmed = await showConfirm({
    title: '移除成员',
    message: `确认从知识库移除「${displayName}」？`,
    confirmText: '移除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.removeKbMember(currentKbId.value, userId, currentOrgId.value || undefined);
    showToast('success', `已移除：${displayName}`);
    await openKbMembers();
  } catch (error) {
    showToast('error', `移除失败：${error.message}`);
  }
}

/** 编辑企业组织名称/描述 */
async function handleEditOrg(org) {
  const form = await showForm({
    title: '编辑组织',
    confirmText: '保存',
    fields: [
      { key: 'name', label: '组织名称', placeholder: '企业或团队名称', defaultValue: org.name || '' },
      { key: 'description', label: '描述（可选）', placeholder: '一句话说明用途', multiline: true,
        defaultValue: '' }
    ]
  });
  const name = form?.name;
  if (!name?.trim()) return;
  try {
    await api.updateOrganization(org.orgId, name.trim(), form.description || '');
    showToast('success', '组织信息已更新');
    await loadOrganizations();
  } catch (error) {
    showToast('error', `更新失败：${error.message}`);
  }
}

/** 删除企业组织（OWNER 专属） */
async function handleDeleteOrg(org) {
  const confirmed = await showConfirm({
    title: '删除组织',
    message: `确认删除组织「${org.name || org.orgId}」？\n\n` +
             '此操作不可恢复。组织下的知识库数据不会自动删除，但组织本身及成员关系将被永久移除。',
    confirmText: '确认删除',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.deleteOrganization(org.orgId);
    showToast('success', `组织「${org.name || org.orgId}」已删除`);
    if (currentOrgId.value === org.orgId) currentOrgId.value = null;
    await loadOrganizations();
    if (currentOrgId.value) await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `删除失败：${error.message}`);
  }
}

/** 退出企业组织（非 OWNER） */
async function handleLeaveOrg(org) {
  const confirmed = await showConfirm({
    title: '退出组织',
    message: `确认退出组织「${org.name || org.orgId}」？`,
    confirmText: '退出',
    variant: 'danger'
  });
  if (!confirmed) return;
  try {
    await api.leaveOrganization(org.orgId);
    showToast('success', `已退出组织「${org.name || org.orgId}」`);
    if (currentOrgId.value === org.orgId) currentOrgId.value = null;
    await loadOrganizations();
    if (currentOrgId.value) await loadKnowledgeBases();
  } catch (error) {
    showToast('error', `退出失败：${error.message}`);
  }
}

// ── 组织邀请成员搜索（与 KB 成员搜索逻辑一致） ─────────────────────────────

function onOrgInviteSearchInput() {
  clearTimeout(_orgInviteSearchTimer);
  const keyword = orgInviteUsername.value;
  if (!keyword || keyword.length < 1) {
    orgInviteSuggestions.value = [];
    orgInviteSuggestionsVisible.value = false;
    orgInviteUserId.value = '';
    return;
  }
  _orgInviteSearchTimer = setTimeout(async () => {
    try {
      orgInviteSuggestions.value = await api.searchUsers(keyword);
      orgInviteSuggestionsVisible.value = orgInviteSuggestions.value.length > 0;
    } catch { orgInviteSuggestions.value = []; }
  }, 300);
}

function selectOrgInviteSuggestion(u) {
  orgInviteUserId.value = u.userId;
  orgInviteUsername.value = `${u.username}（${u.userId}）`;
  orgInviteSuggestions.value = [];
  orgInviteSuggestionsVisible.value = false;
}

function hideOrgInviteSuggestions() {
  setTimeout(() => { orgInviteSuggestionsVisible.value = false; }, 150);
}

/** 执行组织成员邀请 */
async function doInviteOrgMember() {
  if (!currentOrgId.value) return showToast('warning', '请先选择一个组织');

  // 若用户没有从下拉选择，尝试精确匹配
  if (!orgInviteUserId.value) {
    if (orgInviteUsername.value) {
      const results = await api.searchUsers(orgInviteUsername.value).catch(() => []);
      if (results.length === 1) {
        orgInviteUserId.value = results[0].userId;
      } else if (results.length > 1) {
        return showToast('warning', '匹配到多个用户，请从下拉列表中选择');
      } else {
        return showToast('warning', '未找到该用户，请确认用户名是否正确');
      }
    } else {
      return showToast('warning', '请输入用户名搜索并选择');
    }
  }

  try {
    await api.inviteOrgMember(currentOrgId.value, orgInviteUserId.value, orgInviteRole.value);
    showToast('success', `已邀请「${orgInviteUsername.value || orgInviteUserId.value}」加入组织`);
    orgInviteUserId.value = '';
    orgInviteUsername.value = '';
    orgInviteSuggestions.value = [];
  } catch (error) {
    showToast('error', `邀请失败：${error.message}`);
  }
}

function statusLabel(status) {
  if (status === 'DONE') return '完成';
  if (status === 'FAILED') return '失败';
  return '处理中';
}

function kbRoleLabel(role) {
  if (role === 'OWNER') return '拥有者';
  if (role === 'EDITOR') return '编辑者';
  return '只读';
}

function orgRoleLabel(role) {
  if (role === 'OWNER') return '拥有者';
  if (role === 'ADMIN') return '管理员';
  return '成员';
}

function showToast(type, message, duration = 3500) {
  const id = generateId();
  toasts.value.push({ id, type, message });
  if (duration > 0) setTimeout(() => dismissToast(id), duration);
}

function dismissToast(id) {
  toasts.value = toasts.value.filter(item => item.id !== id);
}

function toastIcon(type) {
  const icons = {
    success: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/></svg>',
    error: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
    info: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>',
    warning: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>'
  };
  return icons[type] || icons.info;
}

function showConfirm(options) {
  return openDialog({ ...options, type: 'confirm' });
}

function showPrompt(options) {
  return openDialog({
    ...options,
    type: 'prompt',
    inputValue: options.defaultValue || ''
  });
}

function showForm(options) {
  const formValues = {};
  options.fields.forEach(field => {
    formValues[field.key] = field.defaultValue || '';
  });
  return openDialog({ ...options, type: 'form', formValues });
}

function showChoice(options) {
  return openDialog({
    ...options,
    type: 'choice',
    choiceValue: options.defaultValue || options.choices?.[0]?.value || ''
  });
}

function openDialog(options) {
  return new Promise((resolve) => {
    Object.assign(dialog, {
      visible: true,
      type: options.type || 'confirm',
      title: options.title || '确认操作',
      message: options.message || '',
      variant: options.variant || 'default',
      confirmText: options.confirmText || '确定',
      cancelText: options.cancelText || '取消',
      inputLabel: options.inputLabel || '',
      inputValue: options.inputValue || '',
      placeholder: options.placeholder || '',
      fields: options.fields || [],
      formValues: options.formValues || {},
      choices: options.choices || [],
      choiceValue: options.choiceValue || '',
      resolver: resolve
    });
    nextTick(() => document.querySelector('.dialog-input')?.focus());
  });
}

function resolveDialog(confirmed) {
  if (!dialog.visible) return;
  let result = false;
  if (confirmed) {
    if (dialog.type === 'prompt') result = dialog.inputValue;
    else if (dialog.type === 'form') result = { ...dialog.formValues };
    else if (dialog.type === 'choice') result = dialog.choiceValue;
    else result = true;
  }
  const resolve = dialog.resolver;
  Object.assign(dialog, {
    visible: false,
    resolver: null,
    inputValue: '',
    formValues: {},
    fields: [],
    choices: [],
    choiceValue: ''
  });
  resolve?.(result);
}

function trimText(text, len) {
  return text.length > len ? text.substring(0, len) + '...' : text;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function stripHtml(value) {
  return String(value ?? '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
}

function makeSearchSnippet(text, keyword) {
  const lower = text.toLowerCase();
  const index = lower.indexOf(keyword);
  if (index < 0) return text.slice(0, 90);
  const start = Math.max(0, index - 26);
  const end = Math.min(text.length, index + keyword.length + 54);
  return `${start > 0 ? '...' : ''}${text.slice(start, end)}${end < text.length ? '...' : ''}`;
}
</script>
