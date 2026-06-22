import { formatMarkdown } from '../js/utils.js';

export const MAX_SESSIONS = 50;
export const MAX_MSGS = 100;
export const SAVE_DEBOUNCE = 250;
export const QUICK_MODEL = 'deepseek-v4-flash';
export const EXPERT_MODEL = 'deepseek-v4-pro';
export const MAX_CHAT_MESSAGE_CHARS = 12000;

export function generateId() {
  return 'sess-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export function escapeHtml(str) {
  return String(str ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function renderStreamingText(text) {
  return escapeHtml(text).replace(/\n/g, '<br>');
}

export function trimText(str, max) {
  const s = String(str ?? '');
  return s.length > max ? s.slice(0, max) + '…' : s;
}

export function stripHtml(html) {
  const tmp = document.createElement('div');
  tmp.innerHTML = html;
  return tmp.textContent || '';
}

export function storageKey(userId) {
  return `ai_agent_sessions_${userId}`;
}

export function renderReactBubble(steps, answer, durationMs, answerStreaming = false) {
  const secs = durationMs ? Math.max(1, Math.round(durationMs / 1000)) : '';
  const hasAnswer = answer !== null && typeof answer !== 'undefined';
  const answerText = hasAnswer ? String(answer) : '';
  const stepsHtml = (steps || []).map(step => `
    <div class="react-step">
      <div class="react-step-label">第 ${step.iteration} 步${step.toolName ? ` · ${escapeHtml(step.toolName)}` : ''}</div>
      ${step.thought ? `<div class="react-thought"><span>思考摘要</span><span data-thought-iter="${step.iteration}">${escapeHtml(trimText(step.thought, 220))}</span></div>` : ''}
      ${step.toolName ? `<div class="react-tool"><span>工具调用</span>${escapeHtml(step.toolName)}(${escapeHtml(step.toolArgs || '')})</div>` : ''}
      ${step.pending ? `<div class="react-tool-pending"><span class="typing-dots">●●●</span><span>执行中…</span></div>` : ''}
      ${step.observation ? `<div class="react-obs"><span>观察结果</span>${escapeHtml(trimText(step.observation, 260))}</div>` : ''}
    </div>`).join('');
  const label = hasAnswer
    ? `已思考${secs ? `（用时 ${secs} 秒）` : ''}`
    : `思考中${steps?.length ? `（已完成 ${steps.length} 步）` : ''}…`;
  const stepsBlock = (steps?.length || !hasAnswer) ? `
    <details class="react-steps-container"${hasAnswer ? '' : ' open'}>
      <summary class="react-steps-summary">
        <span class="react-steps-title">${label}</span>
        ${steps?.length ? `<span class="react-steps-count">${steps.length} 步</span>` : ''}
      </summary>
      <div class="react-steps">${stepsHtml}${!hasAnswer ? '<div class="react-step react-step-pending"><span class="typing-dots">●●●</span></div>' : ''}</div>
    </details>` : '';
  const answerHtml = answerStreaming
    ? renderStreamingText(answerText)
    : (answerText ? formatMarkdown(answerText) : '');
  const answerBlock = hasAnswer
    ? `<div class="react-answer">${answerHtml}${answerStreaming ? '<span class="typing-cursor"></span>' : ''}</div>`
    : '';
  return stepsBlock + answerBlock;
}
