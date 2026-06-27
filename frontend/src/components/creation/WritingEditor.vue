<template>
  <div class="creation-writing-stage">
    <div class="creation-flow-steps">
      <span class="done">作品资产</span>
      <span class="active">章节写作</span>
      <span>AI 草稿</span>
      <span>改写/短剧</span>
    </div>
    <slot></slot>
  </div>
</template>

<script setup>
defineOptions({ name: 'WritingEditor' });
</script>
