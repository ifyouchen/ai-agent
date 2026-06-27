<template>
  <Teleport to="body">
    <div v-if="modelValue" class="knowledge-drawer-layer" @click.self="emitClose">
      <aside class="knowledge-drawer" aria-label="知识库上下文">
        <header class="knowledge-drawer-header">
          <div>
            <span>知识上下文</span>
            <h3>{{ org.currentOrgName }}</h3>
          </div>
          <button class="knowledge-icon-btn" type="button" title="关闭" @click="emitClose">×</button>
        </header>

        <div v-if="!org.currentOrgId" class="knowledge-empty-state">
          <strong>请先创建或加入工作空间</strong>
          <p>工作空间决定你能访问哪些知识库。</p>
          <router-link class="knowledge-primary-link" to="/org" @click="emitClose">去组织设置</router-link>
        </div>

        <template v-else>
          <div class="knowledge-current-card" :class="{ active: Boolean(sess.currentKbId) }">
            <span class="knowledge-status-dot" :class="currentHealth.tone"></span>
            <div>
              <strong>{{ sess.currentKbId ? kb.currentKbName || '已关联知识库' : '未使用知识库' }}</strong>
              <p>{{ currentHealth.description }}</p>
            </div>
            <button
              v-if="sess.currentKbId"
              class="knowledge-small-btn"
              type="button"
              @click="clearSelection"
            >
              关闭
            </button>
          </div>

          <div class="knowledge-search-row">
            <input v-model.trim="keyword" type="search" placeholder="搜索知识库" />
            <button class="knowledge-small-btn" type="button" @click="loadKbs">刷新</button>
          </div>

          <div class="knowledge-drawer-actions">
            <button class="knowledge-primary-btn" type="button" @click="createKb">创建知识库</button>
            <router-link class="knowledge-secondary-link" to="/kb" @click="emitClose">进入知识库中心</router-link>
          </div>

          <div class="knowledge-list">
            <div v-if="kb.kbLoading && !kb.knowledgeBases.length" class="knowledge-empty-state compact">
              正在加载知识库…
            </div>
            <div v-else-if="!filteredKbs.length" class="knowledge-empty-state compact">
              <strong>{{ kb.knowledgeBases.length ? '没有匹配的知识库' : '当前组织暂无知识库' }}</strong>
              <p>可以先创建知识库，再上传文档用于问答。</p>
            </div>
            <template v-else>
              <button
                v-for="item in filteredKbs"
                :key="item.id"
                class="knowledge-option"
                :class="{ selected: item.id === sess.currentKbId }"
                type="button"
                @click="selectKb(item)"
              >
                <span class="knowledge-option-icon"></span>
                <span class="knowledge-option-main">
                  <strong>{{ item.name }}</strong>
                  <small>{{ item.description || kbHealthLabel(item) }}</small>
                  <span class="knowledge-option-meta">
                    <span>{{ item.docCount || 0 }} 个文档</span>
                    <span :class="['knowledge-health-pill', kbHealthTone(item)]">{{ kbHealthLabel(item) }}</span>
                  </span>
                </span>
              </button>
            </template>
          </div>
        </template>
      </aside>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useKbStore } from '../../stores/kb.js';
import { useOrgStore } from '../../stores/org.js';
import { useSessionStore } from '../../stores/sessions.js';
import { useUiStore } from '../../stores/ui.js';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
});
const emit = defineEmits(['update:modelValue', 'selected', 'cleared']);

const kb = useKbStore();
const org = useOrgStore();
const sess = useSessionStore();
const ui = useUiStore();
const keyword = ref('');

const filteredKbs = computed(() => {
  const q = keyword.value.toLowerCase();
  const list = [...kb.knowledgeBases];
  list.sort((a, b) => {
    if (a.id === sess.currentKbId) return -1;
    if (b.id === sess.currentKbId) return 1;
    return String(a.name || '').localeCompare(String(b.name || ''), 'zh-CN');
  });
  if (!q) return list;
  return list.filter(item =>
    String(item.name || '').toLowerCase().includes(q)
    || String(item.description || '').toLowerCase().includes(q)
  );
});

const currentHealth = computed(() => {
  if (!sess.currentKbId) {
    return { tone: 'muted', description: '当前对话会按普通模式回答，不检索知识库。' };
  }
  const failed = kb.docs.filter(doc => doc.status === 'FAILED').length;
  const processing = kb.docs.filter(doc => isProcessing(doc.status)).length;
  if (failed) return { tone: 'danger', description: `${failed} 个文档解析失败，回答可能不完整。` };
  if (processing) return { tone: 'warning', description: `${processing} 个文档仍在解析中。` };
  if (!kb.docs.length) return { tone: 'muted', description: '知识库为空，上传文档后才能用于问答。' };
  return { tone: 'ok', description: `${kb.docs.length} 个文档可用于当前对话。` };
});

onMounted(() => {
  if (props.modelValue) loadKbs();
});

watch(() => props.modelValue, (open) => {
  if (open) loadKbs();
});

async function loadKbs() {
  if (!org.currentOrgId) return;
  await kb.loadKbs(org.currentOrgId);
}

async function selectKb(item) {
  if (!item?.id) return;
  sess.setCurrentKb(item.id, org.currentOrgId);
  if (kb.currentKbId !== item.id) await kb.selectKb(item.id, org.currentOrgId);
  ui.showToast('success', `已切换到知识库「${item.name}」`);
  emit('selected', item);
  emitClose();
}

function clearSelection() {
  sess.clearCurrentKb();
  ui.showToast('info', '已关闭知识库问答');
  emit('cleared');
}

async function createKb() {
  const form = await ui.showForm({
    title: '创建知识库',
    confirmText: '创建并使用',
    fields: [
      { key: 'name', label: '知识库名称', placeholder: '例如：产品文档、客户案例、内部 SOP' },
      { key: 'description', label: '描述（可选）', placeholder: '这个知识库主要用于什么？', multiline: true },
    ],
  });
  if (!form?.name?.trim()) return;
  try {
    const created = await kb.createKb(form.name.trim(), form.description?.trim() || '', org.currentOrgId);
    const createdId = created?.id ?? created?.kbId ?? kb.currentKbId;
    if (createdId) sess.setCurrentKb(Number(createdId), org.currentOrgId);
    ui.showToast('success', `已创建并关联「${form.name.trim()}」`);
    emit('selected', kb.currentKb || created);
    emitClose();
  } catch (err) {
    ui.showToast('error', err.message || '创建知识库失败');
  }
}

function emitClose() {
  emit('update:modelValue', false);
}

function kbHealthLabel(item) {
  const failed = Number(item.failedDocCount ?? item.failedDocuments ?? 0);
  const processing = Number(item.processingDocCount ?? item.processingDocuments ?? 0);
  if (failed > 0) return `有失败文档 ${failed}`;
  if (processing > 0) return `解析中 ${processing}`;
  if (!Number(item.docCount || 0)) return '空库';
  return '可用';
}

function kbHealthTone(item) {
  const label = kbHealthLabel(item);
  if (label.startsWith('有失败')) return 'danger';
  if (label.startsWith('解析中')) return 'warning';
  if (label === '空库') return 'muted';
  return 'ok';
}

function isProcessing(status) {
  return ['PROCESSING', 'PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING'].includes(status);
}
</script>

<style scoped>
@import '../../css/components/knowledge-context-drawer.css';
</style>
