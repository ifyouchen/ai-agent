<template>
  <div class="user-avatar" :style="{ background: bgColor }">{{ letter }}</div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  name: { type: String, default: 'U' },
  size: { type: Number, default: 32 },
});

const letter = computed(() => (props.name[0] || 'U').toUpperCase());

/** 根据名称哈希生成稳定的背景色 */
const bgColor = computed(() => {
  const colors = [
    '#4D6BFE','#00A96E','#E53E3E','#D69E2E',
    '#9B59B6','#2B6CB0','#E67E22','#1ABC9C',
  ];
  let hash = 0;
  for (const c of props.name) hash = (hash * 31 + c.charCodeAt(0)) & 0xFFFFFFFF;
  return colors[Math.abs(hash) % colors.length];
});
</script>
