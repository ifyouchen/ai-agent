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

const PURIFY_CONFIG = {
    ALLOWED_TAGS: ['p', 'pre', 'code', 'strong', 'em', 'del', 'h2', 'h3', 'h4',
                   'ul', 'ol', 'li', 'br', 'blockquote', 'hr', 'table', 'thead',
                   'tbody', 'tr', 'th', 'td', 'div', 'span', 'details', 'summary',
                   'button', 'a', 'svg', 'path', 'rect'],
    ALLOWED_ATTR: ['class', 'type', 'href', 'title', 'target', 'rel',
                   'colspan', 'rowspan', 'data-lang', 'viewBox', 'viewbox', 'fill', 'width',
                   'height', 'd', 'x', 'y', 'rx', 'stroke', 'stroke-width',
                   'stroke-linecap', 'stroke-linejoin', 'aria-hidden', 'aria-label'],
    ALLOW_DATA_ATTR: false,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input'],
    FORBID_ATTR: ['style'],
};

const HIGHLIGHT_LANGUAGE_ALIASES = {
    sh: 'bash',
    shell: 'bash',
    zsh: 'bash',
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    py: 'python',
    html: 'xml',
    htm: 'xml',
    vue: 'xml',
    yml: 'yaml',
    md: 'markdown',
};

const markdownRenderer = new Renderer();

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

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
    const language = parseCodeLanguage(lang);
    const normalizedCode = normalizeCodeForLanguage(rawCode, language.source);
    const safeLang = escapeHtml(language.source);
    const langLabel = escapeHtml(language.label);
    const copyIcon = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>';
    const downloadIcon = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3v12m0 0 5-5m-5 5-5-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 21h16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
    const copyBtn = `<button type="button" class="code-tool-btn copy-code-btn" title="复制代码" aria-label="复制代码">${copyIcon}<span>复制</span></button>`;
    const downloadBtn = `<button type="button" class="code-tool-btn download-code-btn" data-lang="${safeLang}" title="下载代码" aria-label="下载代码">${downloadIcon}<span>下载</span></button>`;
    const codeClass = safeLang ? `code-block language-${safeLang}` : 'code-block';
    const codeHtml = highlightedCode(normalizedCode, language.highlight, escaped);
    return `<div class="code-block-wrap" data-lang="${safeLang}"><div class="code-block-toolbar"><span class="code-lang">${langLabel}</span><div class="code-block-actions">${copyBtn}${downloadBtn}</div></div><pre class="${codeClass}"><code>${codeHtml}</code></pre></div>\n`;
};

marked.setOptions({
    gfm: true,
    breaks: false,
    renderer: markdownRenderer,
});

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

function parseCodeLanguage(lang) {
    const source = ((lang || '').match(/^[A-Za-z0-9_+-]+/)?.[0] || '').toLowerCase();
    return {
        source,
        label: source || 'text',
        highlight: HIGHLIGHT_LANGUAGE_ALIASES[source] || source,
    };
}

function normalizeCodeForLanguage(code, langName) {
    return String(code).replace(/\r\n?/g, '\n');
}

function normalizeMarkdownText(text) {
    const normalized = String(text)
        .replace(/\r\n?/g, '\n');

    return normalizeMarkdownSyntaxOutsideCode(wrapPlainSourceIfNeeded(normalized))
        .replace(/^\s*[-*+]\s*-{2,}\s*$/gm, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

function wrapPlainSourceIfNeeded(text) {
    const value = String(text || '').trim();
    if (!value || /```|~~~/.test(value)) return text;
    const inferred = inferPlainSourceLanguage(value);
    return inferred ? `\`\`\`${inferred}\n${value}\n\`\`\`` : text;
}

function inferPlainSourceLanguage(text) {
    const lines = text.split('\n').map(line => line.trim()).filter(Boolean);
    if (lines.length < 6) return '';
    const codeLines = lines.filter(isCodeLikeLine).length;
    if (codeLines / lines.length < 0.5) return '';

    if (/^(package|import)\s+[\w.*]+;|public\s+(?:class|interface|enum)\s+\w+/m.test(text)) return 'java';
    if (/<!doctype\s+html|<html\b|<\/[a-z][\w-]*>/i.test(text)) return 'html';
    if (/\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\b[\s\S]+\b(FROM|TABLE|WHERE|VALUES|SET)\b/i.test(text)) return 'sql';
    if (/^(from\s+\w+\s+import|import\s+\w+|def\s+\w+\s*\(|class\s+\w+[:(])/m.test(text)) return 'python';
    if (/^(const|let|var|function|import|export)\s|\b=>\b/m.test(text)) return 'javascript';
    if (/^\s*(npm|pnpm|yarn|git|cd|mkdir|rm|cp|mv|docker|kubectl)\s+/m.test(text)) return 'shell';
    if (/^[.#]?[\w-]+\s*\{[\s\S]*:[\s\S]*;[\s\S]*\}/m.test(text)) return 'css';
    return '';
}

function isCodeLikeLine(line) {
    return /[;{}()[\]=<>]/.test(line)
        || /^(import|package|public|private|protected|class|interface|enum|def|from|const|let|var|function|if|for|while|return|SELECT|CREATE|INSERT|UPDATE|DELETE)\b/i.test(line)
        || /^\s*(\/\/|\/\*|\*|#|--)/.test(line);
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
        .replace(/[ \t]+\n/g, '\n')
        .replace(/^(#{1,6})(?=\S)/gm, '$1 ')
        .replace(/^(\s{0,3})([-*+])(?=[^\s-*+])/gm, '$1$2 ')
        .replace(/^(\s{0,3})(\d+[.)])(?=\S)/gm, '$1$2 ');
}

export function formatMarkdown(text) {
    if (!text) return '';
    const normalized = normalizeMarkdownText(text);
    const html = marked.parse(normalized);
    return DOMPurify.sanitize(html, PURIFY_CONFIG);
}
