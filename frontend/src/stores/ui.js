/**
 * UI Store — Toast 通知 & Dialog 对话框
 *
 * 集中管理全局 UI 状态，替换原 App.vue 中的 toasts/dialog reactive 变量和辅助函数。
 */
import { defineStore } from 'pinia';
import { reactive, ref } from 'vue';

let toastIdCounter = 0;

export const useUiStore = defineStore('ui', () => {
  // ── Toast ──────────────────────────────────────────────────────────
  const toasts = ref([]);

  function showToast(type, message, duration = 3500) {
    const id = ++toastIdCounter;
    toasts.value.push({ id, type, message });
    if (duration > 0) {
      setTimeout(() => dismissToast(id), duration);
    }
    return id;
  }

  function dismissToast(id) {
    const idx = toasts.value.findIndex(t => t.id === id);
    if (idx >= 0) toasts.value.splice(idx, 1);
  }

  function toastIcon(type) {
    const icons = {
      success: '<svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="m5 12 4 4L19 6" stroke="#00A96E" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
      error:   '<svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M6 6l12 12M18 6 6 18" stroke="#E53E3E" stroke-width="2.2" stroke-linecap="round"/></svg>',
      warning: '<svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M12 8v5M12 17h.01M10.3 4.2 2.8 17.1A2 2 0 0 0 4.5 20h15a2 2 0 0 0 1.7-2.9L13.7 4.2a2 2 0 0 0-3.4 0Z" stroke="#D69E2E" stroke-width="2" stroke-linecap="round"/></svg>',
      info:    '<svg viewBox="0 0 24 24" fill="none" width="16" height="16"><circle cx="12" cy="12" r="9" stroke="#2B6CB0" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="#2B6CB0" stroke-width="2" stroke-linecap="round"/></svg>',
    };
    return icons[type] || icons.info;
  }

  // ── Dialog ─────────────────────────────────────────────────────────
  const dialog = reactive({
    visible:      false,
    type:         'confirm',   // confirm | prompt | form | choice
    variant:      'default',   // default | danger
    title:        '',
    message:      '',
    confirmText:  '确认',
    cancelText:   '取消',
    // prompt
    inputId:      'dlg-input',
    inputLabel:   '',
    inputValue:   '',
    placeholder:  '',
    // form
    fields:       [],
    formValues:   {},
    // choice
    choices:      [],
    choiceValue:  '',
    // internal
    resolver:     null,
  });

  function openDialog(opts) {
    Object.assign(dialog, {
      visible:     true,
      type:        opts.type    || 'confirm',
      variant:     opts.variant || 'default',
      title:       opts.title   || '',
      message:     opts.message || '',
      confirmText: opts.confirmText || '确认',
      cancelText:  opts.cancelText  || '取消',
      inputLabel:  opts.inputLabel  || '',
      inputValue:  opts.defaultValue || '',
      placeholder: opts.placeholder  || '',
      fields:      opts.fields  || [],
      formValues:  opts.fields
        ? Object.fromEntries((opts.fields).map(f => [f.key, f.defaultValue || '']))
        : {},
      choices:     opts.choices    || [],
      choiceValue: opts.defaultValue || opts.choices?.[0]?.value || '',
    });
    return new Promise(resolve => { dialog.resolver = resolve; });
  }

  function resolveDialog(confirmed) {
    dialog.visible = false;
    const resolve = dialog.resolver;
    dialog.resolver = null;
    if (!resolve) return;

    if (!confirmed) { resolve(false); return; }

    switch (dialog.type) {
      case 'prompt':
        resolve(dialog.inputValue?.trim() || null);
        break;
      case 'form':
        resolve({ ...dialog.formValues });
        break;
      case 'choice':
        resolve(dialog.choiceValue);
        break;
      default:
        resolve(true);
    }
  }

  // Shorthand helpers ────────────────────────────────────────────────
  const showConfirm = opts => openDialog({ type: 'confirm', ...opts });
  const showPrompt  = opts => openDialog({ type: 'prompt',  ...opts });
  const showForm    = opts => openDialog({ type: 'form',    ...opts });
  const showChoice  = opts => openDialog({ type: 'choice',  ...opts });

  return {
    // toast
    toasts, showToast, dismissToast, toastIcon,
    // dialog
    dialog, resolveDialog,
    showConfirm, showPrompt, showForm, showChoice,
  };
});
