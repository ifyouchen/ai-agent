<template>
  <div class="creation-modal" @click.self="$emit('close')">
    <form class="creation-dialog" @submit.prevent="$emit('create')">
      <h2>新建作品</h2>
      <input v-model="form.title" required placeholder="作品名" :disabled="creating" />
      <CreationSelect v-model="form.type" :options="typeOptions" :disabled="creating" />
      <textarea v-model="form.description" placeholder="简介" :disabled="creating"></textarea>
      <button class="creation-primary-btn" :disabled="creating">
        <span v-if="creating" class="creation-spinner"></span>
        {{ creating ? '创建中...' : '创建' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import CreationSelect from './CreationSelect.vue';

defineProps({
  form: { type: Object, required: true },
  typeOptions: { type: Array, required: true },
  creating: Boolean,
});

defineEmits(['close', 'create']);
</script>
