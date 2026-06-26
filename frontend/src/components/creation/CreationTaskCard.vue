<template>
  <component :is="compact ? 'div' : 'section'" :class="cardClass">
    <template v-if="compact">
      <h3>{{ title }}</h3>
      <strong>{{ task.currentStep || statusLabel }}</strong>
      <div class="creation-task-progress" :aria-label="`任务进度 ${progress}%`">
        <i :style="{ width: `${progress}%` }"></i>
      </div>
      <small>{{ statusLabel }} · {{ progress }}%</small>
      <button class="creation-primary-btn full compact" type="button" :disabled="!canOpen" @click="$emit('open')">{{ openLabel }}</button>
      <button class="creation-secondary-btn full compact" type="button" :disabled="!!busy" @click="$emit('refresh')">
        {{ busy === 'refresh' ? '刷新中...' : '刷新' }}
      </button>
      <button class="creation-secondary-btn full compact" type="button" :disabled="!canCancel || !!busy" @click="$emit('cancel')">
        {{ busy === 'cancel' ? '终止中...' : '终止' }}
      </button>
      <button class="creation-secondary-btn full compact" type="button" :disabled="!canRetry || !!busy" @click="$emit('retry')">
        {{ busy === 'retry' ? '重试中...' : '重试' }}
      </button>
    </template>

    <template v-else>
      <div class="creation-section-title">
        <div>
          <h2>{{ title }}</h2>
          <p>{{ subtitle }}</p>
        </div>
        <div class="creation-row">
          <button class="creation-primary-btn" type="button" :disabled="!canOpen" @click="$emit('open')">{{ openLabel }}</button>
          <button class="creation-secondary-btn" type="button" :disabled="!!busy" @click="$emit('refresh')">
            {{ busy === 'refresh' ? '刷新中...' : '刷新' }}
          </button>
          <button class="creation-secondary-btn" type="button" :disabled="!canCancel || !!busy" @click="$emit('cancel')">
            {{ busy === 'cancel' ? '终止中...' : '终止' }}
          </button>
          <button class="creation-secondary-btn" type="button" :disabled="!canRetry || !!busy" @click="$emit('retry')">
            {{ busy === 'retry' ? '重试中...' : '重试' }}
          </button>
          <button v-if="showDismiss" class="creation-secondary-btn" type="button" @click="$emit('dismiss')">隐藏</button>
        </div>
      </div>
      <div class="creation-task-body">
        <div>
          <strong>{{ task.currentStep || statusLabel }}</strong>
          <span>{{ statusLabel }} · {{ progress }}%</span>
        </div>
        <div class="creation-task-progress" :aria-label="`任务进度 ${progress}%`">
          <i :style="{ width: `${progress}%` }"></i>
        </div>
      </div>
      <p v-if="tokenUsage?.totalTokens" class="creation-muted">
        预估 Token：{{ tokenUsage.totalTokens }}（输入 {{ tokenUsage.inputTokens }} / 输出 {{ tokenUsage.outputTokens }}）
      </p>
      <p v-if="task.errorMessage" class="creation-risk">{{ task.errorMessage }}</p>
    </template>
  </component>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  task: { type: Object, required: true },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  statusLabel: { type: String, required: true },
  progress: { type: Number, default: 0 },
  openLabel: { type: String, required: true },
  canOpen: Boolean,
  canCancel: Boolean,
  canRetry: Boolean,
  busy: { type: String, default: '' },
  tokenUsage: { type: Object, default: null },
  compact: Boolean,
  showDismiss: Boolean,
});

defineEmits(['open', 'refresh', 'cancel', 'retry', 'dismiss']);

const cardClass = computed(() => [
  props.compact ? 'creation-side-section creation-side-task' : 'creation-panel creation-task-card',
  `status-${props.task.status || 'unknown'}`,
]);
</script>
