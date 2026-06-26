<template>
  <div class="creation-view">
    <header class="creation-header">
      <div>
        <h1>改小说三栏对照</h1>
        <p>{{ reviewSummary }}</p>
      </div>
      <div class="creation-row">
        <button class="creation-secondary-btn" type="button" @click="$emit('back')">返回作品</button>
        <button class="creation-secondary-btn" type="button" :disabled="!!pageBusy" @click="$emit('refresh')">
          {{ pageBusy === 'refresh' ? '刷新中...' : '刷新' }}
        </button>
        <button class="creation-primary-btn" type="button" :disabled="!ready || accepting" @click="$emit('accept')">
          <span v-if="accepting" class="creation-spinner"></span>
          {{ accepting ? '保存中...' : '保存为新版本' }}
        </button>
      </div>
    </header>
    <section v-if="task && !ready" class="creation-panel creation-task-card" :class="`status-${task.status || 'unknown'}`">
      <div class="creation-section-title">
        <div>
          <h2>{{ statusLabelFor(task) }}</h2>
          <p>{{ task.currentStep || '正在准备改写内容' }}</p>
        </div>
        <div class="creation-row">
          <button class="creation-secondary-btn" type="button" :disabled="!!pageBusy" @click="$emit('refresh')">
            {{ pageBusy === 'refresh' ? '刷新中...' : '刷新' }}
          </button>
          <button class="creation-secondary-btn" type="button" :disabled="!canCancel || !!pageBusy" @click="$emit('cancel')">
            {{ pageBusy === 'cancel' ? '终止中...' : '终止' }}
          </button>
          <button class="creation-secondary-btn" type="button" :disabled="!canRetry || !!pageBusy" @click="$emit('retry')">
            {{ pageBusy === 'retry' ? '重试中...' : '重试' }}
          </button>
        </div>
      </div>
      <div class="creation-task-body">
        <div>
          <strong>{{ task.currentStep || statusLabelFor(task) }}</strong>
          <span>{{ statusLabelFor(task) }} · {{ progress }}%</span>
        </div>
        <div class="creation-task-progress" :aria-label="`改写任务进度 ${progress}%`">
          <i :style="{ width: `${progress}%` }"></i>
        </div>
      </div>
      <p v-if="task.errorMessage" class="creation-risk">{{ task.errorMessage }}</p>
    </section>
    <section v-if="ready" class="creation-rewrite-summary">
      <span>共 {{ segments.length }} 段</span>
      <span>采用 AI {{ acceptedCount }} 段</span>
      <span>保留原文 {{ rejectedCount }} 段</span>
    </section>
    <section v-if="ready" class="creation-columns creation-rewrite-columns">
      <article>
        <h2>原文</h2>
        <div v-for="(segment, index) in segments" :key="index" class="creation-rewrite-segment">
          <strong>第 {{ index + 1 }} 段</strong>
          <p>{{ segment.source }}</p>
        </div>
      </article>
      <article>
        <h2>AI 改写稿</h2>
        <div v-for="(segment, index) in segments" :key="index" class="creation-rewrite-choice" :class="`status-${segment.status || 'accepted'}`">
          <div class="creation-rewrite-choice-head">
            <strong>第 {{ index + 1 }} 段</strong>
            <span>{{ segment.status === 'rejected' ? '将保留原文' : '将采用 AI 改写' }}</span>
          </div>
          <textarea v-model="segment.rewritten"></textarea>
          <div class="creation-segment-actions">
            <button type="button" :class="{ active: segment.status !== 'rejected' }" @click="$emit('set-segment-status', segment, 'accepted')">采用 AI 改写</button>
            <button type="button" :class="{ active: segment.status === 'rejected' }" @click="$emit('set-segment-status', segment, 'rejected')">保留原文</button>
          </div>
        </div>
      </article>
      <article>
        <h2>本次改写说明</h2>
        <div class="creation-rewrite-note">
          <strong>{{ task.summaryNote || '本次改写已完成，请逐段确认采用 AI 改写或保留原文。' }}</strong>
          <p>保存为新版本时，采用 AI 的段落会写入改写稿；选择保留原文的段落会写回原文。</p>
        </div>
        <label class="creation-field">指定要求<textarea v-model="form.instruction" rows="4" placeholder="例如：更口语、更狠一点、保留某个设定"></textarea></label>
        <button class="creation-secondary-btn full" type="button" :disabled="retrying || !!pageBusy" @click="$emit('retry')">
          <span v-if="retrying" class="creation-spinner dark"></span>
          {{ retrying ? '提交中...' : '按要求再改一次' }}
        </button>
        <div v-if="segmentNotes.length" class="creation-rewrite-note-list">
          <p v-for="item in segmentNotes" :key="item.index">第 {{ item.index }} 段：{{ item.note }}</p>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
defineProps({
  task: { type: Object, default: null },
  reviewSummary: { type: String, required: true },
  ready: Boolean,
  accepting: Boolean,
  retrying: Boolean,
  pageBusy: { type: String, default: '' },
  canCancel: Boolean,
  canRetry: Boolean,
  progress: { type: Number, default: 0 },
  segments: { type: Array, default: () => [] },
  acceptedCount: { type: Number, default: 0 },
  rejectedCount: { type: Number, default: 0 },
  segmentNotes: { type: Array, default: () => [] },
  form: { type: Object, required: true },
  statusLabelFor: { type: Function, required: true },
});

defineEmits(['back', 'refresh', 'accept', 'cancel', 'retry', 'set-segment-status']);
</script>
