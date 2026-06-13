<template>
  <div class="user-avatar" :style="avatarStyle" :aria-label="`用户头像：${displayName}`">{{ letter }}</div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  name: { type: String, default: 'U' },
  size: { type: Number, default: 32 },
});

const displayName = computed(() => props.name || 'User');
const letter = computed(() => (displayName.value[0] || 'U').toUpperCase());

const avatarStyle = computed(() => {
  const size = `${props.size}px`;
  return {
    width: size,
    height: size,
    background: 'linear-gradient(135deg, #F8FAFF 0%, #EEF3FF 100%)',
    color: '#4D6BFE',
    border: '1px solid #DCE6FF',
    boxShadow: props.size >= 56
      ? '0 10px 28px rgba(79, 103, 245, 0.14)'
      : '0 2px 8px rgba(79, 103, 245, 0.10)',
  };
});
</script>
