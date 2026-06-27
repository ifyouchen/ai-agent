<template>
  <div v-if="(totalPages || 0) > 1" class="admin-pagination">
    <button class="admin-small-btn" type="button" :disabled="page === 0" @click="$emit('prev')">上一页</button>
    <span class="admin-muted">第 {{ page + 1 }} / {{ totalPages }} 页</span>
    <button class="admin-small-btn" type="button" :disabled="page >= totalPages - 1" @click="$emit('next')">下一页</button>
  </div>
</template>

<script setup>
defineProps({
  page: { type: Number, required: true },
  totalPages: { type: Number, default: 0 },
});

defineEmits(['prev', 'next']);
</script>
