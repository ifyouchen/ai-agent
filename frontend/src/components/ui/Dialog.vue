<template>
  <Teleport to="body">
    <div
      v-if="ui.dialog.visible"
      class="modal-overlay app-dialog-overlay"
      @click.self="ui.resolveDialog(false)"
    >
      <div class="modal-content app-dialog" :class="`dialog-${ui.dialog.variant}`">
        <div class="dialog-icon">
          <svg v-if="ui.dialog.variant === 'danger'" viewBox="0 0 24 24" fill="none">
            <path d="M12 8v5M12 17h.01M10.3 4.2 2.8 17.1A2 2 0 0 0 4.5 20h15a2 2 0 0 0 1.7-2.9L13.7 4.2a2 2 0 0 0-3.4 0Z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none">
            <path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" stroke="currentColor" stroke-width="1.8"/>
            <path d="M8.5 9.8 12 7.8l3.5 2-3.5 2-3.5-2Z" stroke="currentColor" stroke-width="1.8"/>
          </svg>
        </div>
        <div class="dialog-main">
          <div class="dialog-header">
            <h3>{{ ui.dialog.title }}</h3>
            <button class="modal-close" type="button" @click="ui.resolveDialog(false)">×</button>
          </div>
          <p v-if="ui.dialog.message" class="dialog-message">{{ ui.dialog.message }}</p>

          <!-- prompt 类型：单行输入 -->
          <div v-if="ui.dialog.type === 'prompt'" class="dialog-fields">
            <label class="dialog-field-label" :for="ui.dialog.inputId">{{ ui.dialog.inputLabel }}</label>
            <input
              :id="ui.dialog.inputId"
              v-model.trim="ui.dialog.inputValue"
              class="dialog-input"
              type="text"
              :placeholder="ui.dialog.placeholder"
              autofocus
              @keydown.enter.prevent="ui.resolveDialog(true)"
              @keydown.esc.prevent="ui.resolveDialog(false)"
            />
          </div>

          <!-- form 类型：多字段表单 -->
          <div v-if="ui.dialog.type === 'form'" class="dialog-fields">
            <label
              v-for="field in ui.dialog.fields"
              :key="field.key"
              class="dialog-field"
            >
              <span class="dialog-field-label">{{ field.label }}</span>
              <textarea
                v-if="field.multiline"
                v-model.trim="ui.dialog.formValues[field.key]"
                class="dialog-input dialog-textarea"
                :placeholder="field.placeholder"
                rows="3"
                @keydown.esc.prevent="ui.resolveDialog(false)"
              ></textarea>
              <input
                v-else
                v-model.trim="ui.dialog.formValues[field.key]"
                class="dialog-input"
                :type="field.type || 'text'"
                :placeholder="field.placeholder"
                @keydown.enter.prevent="ui.resolveDialog(true)"
                @keydown.esc.prevent="ui.resolveDialog(false)"
              />
            </label>
          </div>

          <!-- choice 类型：单选列表 -->
          <div v-if="ui.dialog.type === 'choice'" class="dialog-choice-list">
            <button
              v-for="choice in ui.dialog.choices"
              :key="choice.value"
              class="dialog-choice"
              :class="{ active: ui.dialog.choiceValue === choice.value }"
              type="button"
              @click="ui.dialog.choiceValue = choice.value"
            >
              <span>{{ choice.label }}</span>
              <small>{{ choice.desc }}</small>
            </button>
          </div>

          <div class="dialog-actions">
            <button class="dialog-btn secondary" type="button" @click="ui.resolveDialog(false)">
              {{ ui.dialog.cancelText }}
            </button>
            <button
              class="dialog-btn primary"
              :class="{ danger: ui.dialog.variant === 'danger' }"
              type="button"
              @click="ui.resolveDialog(true)"
            >
              {{ ui.dialog.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { useUiStore } from '../../stores/ui.js';
const ui = useUiStore();
</script>
