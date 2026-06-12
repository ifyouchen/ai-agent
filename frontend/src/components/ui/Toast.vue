<template>
  <Teleport to="body">
    <div class="toast-container">
      <div
        v-for="toast in ui.toasts"
        :key="toast.id"
        class="toast toast-visible"
        :class="`toast-${toast.type}`"
      >
        <span class="toast-icon" v-html="ui.toastIcon(toast.type)"></span>
        <span class="toast-msg">{{ toast.message }}</span>
        <button class="toast-close" type="button" @click="ui.dismissToast(toast.id)">×</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { useUiStore } from '../../stores/ui.js';
const ui = useUiStore();
</script>
