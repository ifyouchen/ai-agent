/**
 * 工具函数集合
 */
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import css from 'highlight.js/lib/languages/css';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import markdown from 'highlight.js/lib/languages/markdown';
import python from 'highlight.js/lib/languages/python';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';
import { marked, Renderer } from 'marked';

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('css', css);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('markdown', markdown);
hljs.registerLanguage('python', python);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('yaml', yaml);
hljs.registerAliases(['sh', 'shell'], { languageName: 'bash' });
hljs.registerAliases(['js'], { languageName: 'javascript' });
hljs.registerAliases(['ts'], { languageName: 'typescript' });
hljs.registerAliases(['py'], { languageName: 'python' });
hljs.registerAliases(['html', 'vue'], { languageName: 'xml' });
hljs.registerAliases(['md'], { languageName: 'markdown' });
hljs.registerAliases(['yml'], { languageName: 'yaml' });

/**
 * DOMPurify 白名单配置
 *
 * 精确匹配 formatMarkdown 产生的标签集合，拒绝所有其他标签和属性，
 * 防止 LLM 输出中的恶意 HTML（如 <img onerror=...>、<script>）执行。
 */
const PURIFY_CONFIG = {
    ALLOWED_TAGS: ['p', 'pre', 'code', 'strong', 'em', 'del', 'h2', 'h3', 'h4',
                   'ul', 'ol', 'li', 'br', 'blockquote', 'hr', 'table', 'thead',
                   'tbody', 'tr', 'th', 'td', 'div', 'span', 'details', 'summary',
                   'button', 'a', 'svg', 'path', 'rect'],
    ALLOWED_ATTR: ['class', 'type', 'data-code', 'href', 'title', 'target', 'rel',
                   'colspan', 'rowspan', 'data-lang', 'viewBox', 'viewbox', 'fill', 'width',
                   'height', 'd', 'x', 'y', 'rx', 'stroke', 'stroke-width',
                   'stroke-linecap', 'stroke-linejoin', 'aria-hidden'],
    ALLOW_DATA_ATTR: false,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input'],
    FORBID_ATTR: ['style'],
};

const markdownRenderer = new Renderer();

markdownRenderer.html = ({ text }) => escapeHtml(text);

markdownRenderer.heading = function ({ tokens, depth }) {
    const inner = this.parser.parseInline(tokens);
    const level = depth <= 2 ? 2 : 3;
    const cls = level === 2 ? 'md-h2' : 'md-h3';
    return `<h${level} class="${cls}">${inner}</h${level}>\n`;
};

markdownRenderer.codespan = ({ text }) =>
    `<code class="inline-code">${escapeHtml(text)}</code>`;

markdownRenderer.code = ({ text, lang, escaped }) => {
    const rawCode = text || '';
    const langName = ((lang || '').match(/^[A-Za-z0-9_-]+/)?.[0] || '').toLowerCase();
    const normalizedCode = normalizeCodeForLanguage(rawCode, langName);
    const safeLang = escapeHtml(langName);
    const langLabel = safeLang || 'text';
    const copyIcon = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>';
    const downloadIcon = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3v12m0 0 5-5m-5 5-5-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 21h16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
    const copyBtn = `<button type="button" class="code-tool-btn copy-code-btn" data-code="${encodeURIComponent(normalizedCode)}" title="复制代码">${copyIcon}<span>复制</span></button>`;
    const downloadBtn = `<button type="button" class="code-tool-btn download-code-btn" data-code="${encodeURIComponent(normalizedCode)}" data-lang="${safeLang}" title="下载代码">${downloadIcon}<span>下载</span></button>`;
    const codeClass = safeLang ? `code-block language-${safeLang}` : 'code-block';
    const codeHtml = highlightedCode(normalizedCode, langName, escaped);
    return `<div class="code-block-wrap"><div class="code-block-toolbar"><span class="code-lang">${langLabel}</span><div class="code-block-actions">${copyBtn}${downloadBtn}</div></div><pre class="${codeClass}"><code>${codeHtml}</code></pre></div>\n`;
};

marked.setOptions({
    gfm: true,
    breaks: false,
    renderer: markdownRenderer,
});

/**
 * Markdown 渲染 + DOMPurify 二次过滤
 *
 * 渲染流程：
 *   1. 预处理连续空行和常见占位列表项，避免 AI 回复视觉发散
 *   2. marked 负责 Markdown 结构化渲染
 *   3. DOMPurify 白名单过滤，作为最后一道防线
 *
 * 支持：段落、列表、标题、加粗、斜体、代码块、行内代码、表格等常见 Markdown
 */
export function formatMarkdown(text) {
    if (!text) return '';
    const normalized = normalizeMarkdownText(text);
    const html = marked.parse(normalized);
    return DOMPurify.sanitize(html, PURIFY_CONFIG);
}

function normalizeMarkdownText(text) {
    const normalized = String(text)
        .replace(/\r\n?/g, '\n')
        .replace(/[ \t]+\n/g, '\n');

    return normalizeMarkdownSyntaxOutsideCode(normalized)
        .replace(/^\s*[-*+]\s*-{2,}\s*$/gm, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

function normalizeMarkdownSyntaxOutsideCode(text) {
    return text
        .split(/(```[\s\S]*?```|~~~[\s\S]*?~~~)/g)
        .map((part) => {
            if (part.startsWith('```') || part.startsWith('~~~')) {
                return part;
            }
            return normalizeMarkdownBlock(part);
        })
        .join('');
}

function normalizeMarkdownBlock(text) {
    return text
        .replace(/^(#{1,6})(?=\S)/gm, '$1 ')
        .replace(/^(\s{0,3})([-*+])(?=[^\s-*+])/gm, '$1$2 ')
        .replace(/^(\s{0,3})(\d+[.)])(?=\S)/gm, '$1$2 ');
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function highlightedCode(rawCode, langName, escaped) {
    if (escaped) return rawCode;
    try {
        if (langName && hljs.getLanguage(langName)) {
            return hljs.highlight(rawCode, { language: langName }).value;
        }
        return hljs.highlightAuto(rawCode).value;
    } catch {
        return escapeHtml(rawCode);
    }
}

function normalizeCodeForLanguage(code, langName) {
    const normalizedLang = String(langName || '').toLowerCase();
    if (normalizedLang === 'java') {
        return normalizeJavaCode(code);
    }
    if (['c', 'cpp', 'c++', 'csharp', 'cs'].includes(normalizedLang)) {
        return normalizeCStyleCode(code);
    }
    if (['javascript', 'js', 'typescript', 'ts'].includes(normalizedLang)) {
        return normalizeJsLikeCode(code);
    }
    if (['python', 'py'].includes(normalizedLang)) {
        return normalizePythonCode(code);
    }
    if (normalizedLang === 'json') {
        return normalizeJsonCode(code);
    }
    return code;
}

function normalizeJavaCode(code) {
    let result = normalizeCStyleCode(code);
    const modifiers = 'public|private|protected|static|final|abstract|synchronized|native|strictfp';
    const types = `${modifiers}|void|boolean|byte|short|int|long|float|double|char|String|class|interface|enum`;
    const modifierJoinPattern = new RegExp(`\\b(${modifiers})(?=(${types}))`, 'g');

    for (let i = 0; i < 4; i++) {
        result = result.replace(modifierJoinPattern, '$1 ');
    }

    return result
        .replace(/\b(class|interface|enum)(?=[A-Z_$])/g, '$1 ')
        .replace(/\b(void|boolean|byte|short|int|long|float|double|char|String)(?=[A-Za-z_$])/g, '$1 ')
        .replace(/\b(String\s*\[\])(?=[A-Za-z_$])/g, '$1 ')
        .replace(/\bmain\s*\(\s*String\s*\[\]\s*([A-Za-z_$][\w$]*)\s*\)/g, 'main(String[] $1)')
        .replace(/\b(class|interface|enum)\s+([A-Za-z_$][\w$]*)\s*\{/g, '$1 $2 {')
        .replace(/\)\s*\{/g, ') {');
}

function normalizeCStyleCode(code) {
    return String(code)
        .replace(/^(\s*)(class|struct|enum|interface)(?=[A-Z_$])/gm, '$1$2 ')
        .replace(/^(\s*)(void|bool|boolean|char|byte|short|int|long|float|double|String|string|auto)(?=[A-Za-z_$])/gm, '$1$2 ')
        .replace(/\b(if|for|while|switch|catch)\s*\(/g, '$1 (')
        .replace(/\)\s*\{/g, ') {')
        .replace(/\b(class|struct|enum|interface)\s+([A-Za-z_$][\w$]*)\s*\{/g, '$1 $2 {');
}

function normalizeJsLikeCode(code) {
    return normalizeCStyleCode(code)
        .replace(/^(\s*)(async)(?=function\b|[A-Za-z_$])/gm, '$1$2 ')
        .replace(/^(\s*)(function)(?=[A-Za-z_$])/gm, '$1$2 ')
        .replace(/^(\s*)(const|let|var)(?=[A-Za-z_$])/gm, '$1$2 ')
        .replace(/^(\s*)(export|default|import|from|return|await|yield)(?=[A-Za-z_$])/gm, '$1$2 ')
        .replace(/\b(function)\s+([A-Za-z_$][\w$]*)\s*\(/g, '$1 $2(');
}

function normalizePythonCode(code) {
    return String(code)
        .replace(/^(\s*)(def)(?=[A-Za-z_])/gm, '$1$2 ')
        .replace(/^(\s*)(class)(?=[A-Za-z_])/gm, '$1$2 ')
        .replace(/^(\s*)(import|from)(?=[A-Za-z_])/gm, '$1$2 ')
        .replace(/^(\s*)(if|elif|for|while|with|except)(?=[A-Za-z_(])/gm, '$1$2 ')
        .replace(/\b(def|class)\s+([A-Za-z_][\w]*)\s*\(/g, '$1 $2(');
}

function normalizeJsonCode(code) {
    try {
        return JSON.stringify(JSON.parse(code), null, 2);
    } catch {
        return code;
    }
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

