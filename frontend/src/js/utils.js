/**
 * 轻量工具函数集合（不含 Markdown 相关重型依赖）
 */

export async function copyText(text) {
    const value = String(text ?? '');
    if (!value) return false;

    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(value);
            return true;
        } catch {
        }
    }

    const textarea = document.createElement('textarea');
    textarea.value = value;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.top = '-1000px';
    textarea.style.left = '-1000px';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);

    const selection = document.getSelection();
    const selectedRange = selection && selection.rangeCount > 0
        ? selection.getRangeAt(0)
        : null;

    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);

    let ok = false;
    try {
        ok = document.execCommand('copy');
    } catch {
        ok = false;
    } finally {
        document.body.removeChild(textarea);
        if (selectedRange && selection) {
            selection.removeAllRanges();
            selection.addRange(selectedRange);
        }
    }

    return ok;
}

/**
 * 代码块复制：事件委托处理器（挂载到 document 上）
 * 在 App.vue 的 onMounted 中调用 setupCopyCodeHandler() 一次即可
 */
export function setupCopyCodeHandler(onCopyFailure = null) {
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.copy-code-btn');
        if (!btn) return;
        const code = decodeURIComponent(btn.dataset.code || '');
        copyText(code).then((ok) => {
            if (!ok) {
                onCopyFailure?.();
                return;
            }
            const label = btn.querySelector('span') || btn;
            const orig = label.textContent;
            label.textContent = '已复制';
            btn.classList.add('copied');
            setTimeout(() => { label.textContent = orig; btn.classList.remove('copied'); }, 1800);
        }).catch(() => {});
    });

    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.download-code-btn');
        if (!btn) return;
        const code = decodeURIComponent(btn.dataset.code || '');
        const lang = btn.dataset.lang || 'txt';
        downloadCode(code, lang);
    });
}

function downloadCode(code, lang) {
    const ext = codeFileExtension(lang);
    const blob = new Blob([code], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `code-${new Date().toISOString().replace(/[:.]/g, '-')}.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
}

function codeFileExtension(lang) {
    const map = {
        javascript: 'js',
        js: 'js',
        typescript: 'ts',
        ts: 'ts',
        java: 'java',
        python: 'py',
        py: 'py',
        vue: 'vue',
        html: 'html',
        css: 'css',
        json: 'json',
        xml: 'xml',
        yaml: 'yml',
        yml: 'yml',
        markdown: 'md',
        md: 'md',
        shell: 'sh',
        bash: 'sh',
        sql: 'sql',
    };
    return map[String(lang || '').toLowerCase()] || 'txt';
}

/**
 * 文件扩展名 → 图标/类名
 */
const SVG_PDF  = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#E53E3E"><path d="M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z"/></svg>';
const SVG_WORD = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#2B6CB0"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11zM8 15.5h1.5l1 3.5 1-3.5H13l-1.75 5H9.75L8 15.5z"/></svg>';
const SVG_FILE = '<svg width="18" height="18" viewBox="0 0 24 24" fill="#718096"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z"/></svg>';

const iconCache = new Map();

export function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    if (iconCache.has(ext)) return iconCache.get(ext);
    const map = {
        pdf:  { icon: SVG_PDF,  cls: 'pdf' },
        doc:  { icon: SVG_WORD, cls: 'word' },
        docx: { icon: SVG_WORD, cls: 'word' },
        txt:  { icon: SVG_FILE, cls: 'txt' },
        md:   { icon: SVG_FILE, cls: 'txt' },
    };
    const result = map[ext] ?? { icon: SVG_FILE, cls: 'txt' };
    iconCache.set(ext, result);
    return result;
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
