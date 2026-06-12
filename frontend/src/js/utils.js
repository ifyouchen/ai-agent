/**
 * 工具函数集合
 */
import DOMPurify from 'dompurify';

/**
 * DOMPurify 白名单配置
 *
 * 精确匹配 formatMarkdown 产生的标签集合，拒绝所有其他标签和属性，
 * 防止 LLM 输出中的恶意 HTML（如 <img onerror=...>、<script>）执行。
 */
const PURIFY_CONFIG = {
    ALLOWED_TAGS: ['pre', 'code', 'strong', 'em', 'h2', 'h3', 'li', 'br',
                   'div', 'span', 'details', 'summary', 'button'],
    ALLOWED_ATTR: ['class', 'type', 'data-code'],
};

/**
 * 简易 Markdown 渲染 + DOMPurify 二次过滤
 *
 * 渲染流程：
 *   1. 先 HTML 转义（& < > "），防止原始 HTML 直通
 *   2. 应用 Markdown 正则，仅生成白名单标签
 *   3. DOMPurify 白名单过滤，作为最后一道防线
 *
 * 支持：代码块、行内代码、加粗、斜体、h2/h3、列表、换行
 */
export function formatMarkdown(text) {
    if (!text) return '';
    const html = text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        // 代码块：添加语言标签 + 复制按钮
        .replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
            const langLabel = lang ? `<span class="code-lang">${lang}</span>` : '';
            const copyBtn   = `<button type="button" class="copy-code-btn" data-code="${encodeURIComponent(code)}">复制</button>`;
            return `<div class="code-block-wrap">${langLabel}${copyBtn}<pre class="code-block"><code>${code}</code></pre></div>`;
        })
        .replace(/`([^`]+)`/g,
            '<code class="inline-code">$1</code>')
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.+?)\*/g, '<em>$1</em>')
        .replace(/^### (.+)$/gm, '<h3 class="md-h3">$1</h3>')
        .replace(/^## (.+)$/gm, '<h2 class="md-h2">$1</h2>')
        .replace(/^- (.+)$/gm, '<li>$1</li>')
        .replace(/\n/g, '<br>');
    return DOMPurify.sanitize(html, PURIFY_CONFIG);
}

/**
 * 代码块复制：事件委托处理器（挂载到 document 上）
 * 在 App.vue 的 onMounted 中调用 setupCopyCodeHandler() 一次即可
 */
export function setupCopyCodeHandler() {
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.copy-code-btn');
        if (!btn) return;
        const code = decodeURIComponent(btn.dataset.code || '');
        navigator.clipboard.writeText(code).then(() => {
            const orig = btn.textContent;
            btn.textContent = '已复制!';
            btn.classList.add('copied');
            setTimeout(() => { btn.textContent = orig; btn.classList.remove('copied'); }, 1800);
        }).catch(() => {});
    });
}

/**
 * 文件扩展名 → 图标/类名
 */
const SVG_PDF  = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#E53E3E"><path d="M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z"/></svg>';
const SVG_WORD = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#2B6CB0"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11zM8 15.5h1.5l1 3.5 1-3.5H13l-1.75 5H9.75L8 15.5z"/></svg>';
const SVG_FILE = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#718096"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z"/></svg>';

export function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const map = {
        pdf:  { icon: SVG_PDF,  cls: 'pdf' },
        doc:  { icon: SVG_WORD, cls: 'word' },
        docx: { icon: SVG_WORD, cls: 'word' },
        txt:  { icon: SVG_FILE, cls: 'txt' },
        md:   { icon: SVG_FILE, cls: 'txt' },
    };
    return map[ext] ?? { icon: SVG_FILE, cls: 'txt' };
}

/**
 * 格式化文件大小
 */
export function formatFileSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 防抖函数
 */
export function debounce(fn, delay = 300) {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delay);
    };
}

