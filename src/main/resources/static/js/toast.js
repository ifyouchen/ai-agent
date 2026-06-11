/**
 * Toast 通知组件
 */

const ICONS = { success: '✅', error: '❌', info: 'ℹ️', warning: '⚠️' };
const AUTO_DISMISS_MS = 3500;

/**
 * 显示 Toast 通知
 * @param {'success'|'error'|'info'|'warning'} type
 * @param {string} message
 * @param {number} [duration] 自动消失时间(ms)，0 表示不自动消失
 */
export function showToast(type, message, duration = AUTO_DISMISS_MS) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `
        <span class="toast-icon">${ICONS[type] ?? 'ℹ️'}</span>
        <span class="toast-msg">${message}</span>
        <button class="toast-close" onclick="this.parentElement.remove()">×</button>
    `;
    container.appendChild(toast);

    // 入场动画
    requestAnimationFrame(() => toast.classList.add('toast-visible'));

    if (duration > 0) {
        setTimeout(() => {
            toast.classList.remove('toast-visible');
            toast.addEventListener('transitionend', () => toast.remove(), { once: true });
        }, duration);
    }
}

