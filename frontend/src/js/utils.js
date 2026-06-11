/**
 * 工具函数集合
 */

/**
 * 简易 Markdown 渲染（不引入外部库）
 * 支持：代码块、行内代码、加粗、斜体、h2/h3、列表、换行
 */
export function formatMarkdown(text) {
    if (!text) return '';
    return text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/```(\w*)\n?([\s\S]*?)```/g,
            '<pre class="code-block"><code>$2</code></pre>')
        .replace(/`([^`]+)`/g,
            '<code class="inline-code">$1</code>')
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.+?)\*/g, '<em>$1</em>')
        .replace(/^### (.+)$/gm, '<h3 class="md-h3">$1</h3>')
        .replace(/^## (.+)$/gm, '<h2 class="md-h2">$1</h2>')
        .replace(/^- (.+)$/gm, '<li>$1</li>')
        .replace(/\n/g, '<br>');
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

