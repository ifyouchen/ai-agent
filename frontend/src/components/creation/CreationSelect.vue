<template>
  <div
    class="creation-select"
    :class="{ open, disabled }"
    @focusout="closeOnFocusOut"
    @keydown="onKeydown"
  >
    <button
      class="creation-select-trigger"
      type="button"
      :disabled="disabled"
      aria-haspopup="listbox"
      :aria-expanded="open ? 'true' : 'false'"
      @click="toggle"
    >
      <span>{{ current?.label ?? placeholder }}</span>
      <i aria-hidden="true"></i>
    </button>
    <div v-if="open" class="creation-select-menu" role="listbox">
      <button
        v-for="option in options"
        :key="option.value"
        class="creation-select-option"
        :class="{ selected: option.value === modelValue }"
        type="button"
        role="option"
        :disabled="option.disabled"
        :aria-selected="option.value === modelValue ? 'true' : 'false'"
        @click="selectOption(option)"
      >
        <span>{{ option.label }}</span>
        <em v-if="option.value === modelValue">✓</em>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';

const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: null },
  options: { type: Array, default: () => [] },
  disabled: Boolean,
  placeholder: { type: String, default: '请选择' },
});

const emit = defineEmits(['update:modelValue', 'change']);
const open = ref(false);
const current = computed(() => props.options.find(option => option.value === props.modelValue));

function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
}

function selectOption(option) {
  if (option.disabled) return;
  emit('update:modelValue', option.value);
  emit('change', option.value);
  open.value = false;
}

function closeOnFocusOut(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) open.value = false;
}

function onKeydown(event) {
  if (event.key === 'Escape') open.value = false;
}
</script>
