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
export function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const map = {
        pdf:  { icon: '📕', cls: 'pdf' },
        doc:  { icon: '📘', cls: 'word' },
        docx: { icon: '📘', cls: 'word' },
        txt:  { icon: '📄', cls: 'txt' },
        md:   { icon: '📝', cls: 'txt' },
    };
    return map[ext] ?? { icon: '📄', cls: 'txt' };
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

