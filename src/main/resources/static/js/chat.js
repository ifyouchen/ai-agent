/**
 * 聊天面板组件
 */
import {state} from './state.js';
import {chatStream, chatSync, clearMemory as apiClearMemory} from './api.js';
import {formatMarkdown} from './utils.js';
import {showToast} from './toast.js';
import {updateSessionTitle} from './session.js';

// ── 欢迎页 HTML 模板 ─────────────────────────────────────────

const WELCOME_HTML = `
<div class="welcome" id="welcomeScreen">
    <div class="welcome-icon">🤖</div>
    <h2>你好，我是 AI Agent</h2>
    <p>我可以回答问题、查询信息、帮你完成各种任务</p>
    <div class="quick-prompts" id="quickPrompts">
        <div class="quick-prompt" data-msg="帮我查一下订单 #12345 的状态">📦 查询订单状态</div>
        <div class="quick-prompt" data-msg="北京今天天气怎么样？">🌤️ 查询天气</div>
        <div class="quick-prompt" data-msg="帮我介绍一下你能做什么">🤔 了解功能</div>
        <div class="quick-prompt" data-msg="查询用户 U001 的账户余额">💰 查询账户</div>
    </div>
</div>`;

// ── 公共方法（供 session.js 调用）────────────────────────────

export function clearChatUI() {
    const messages = document.getElementById('chatMessages');
    if (messages) {
        messages.innerHTML = WELCOME_HTML;
        bindQuickPrompts();
    }
}

// ── 初始化 ────────────────────────────────────────────────────

export function initChat() {
    bindQuickPrompts();
    initInputArea();
}

function bindQuickPrompts() {
    document.querySelectorAll('.quick-prompt').forEach(el => {
        el.addEventListener('click', () => sendQuick(el.dataset.msg));
    });
}

function initInputArea() {
    const input = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendBtn');
    const streamToggle = document.getElementById('streamToggle');

    if (input) {
        input.addEventListener('input', () => autoResize(input));
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && e.ctrlKey) {
                e.preventDefault();
                sendMessage();
            }
        });
    }

    if (sendBtn) {
        sendBtn.addEventListener('click', sendMessage);
    }

    if (streamToggle) {
        streamToggle.addEventListener('click', () => {
            state.streamEnabled = !state.streamEnabled;
            streamToggle.classList.toggle('on', state.streamEnabled);
        });
    }

    // 深度推理（ReAct）开关
    const reactToggle = document.getElementById('reactToggle');
    if (reactToggle) {
        reactToggle.addEventListener('click', () => {
            state.reactEnabled = !state.reactEnabled;
            reactToggle.classList.toggle('on', state.reactEnabled);
            if (state.reactEnabled) {
                showToast('info', '🧠 深度推理已开启，适合复杂多步任务');
            }
        });
    }

    // 清除记忆按钮
    const clearBtn = document.getElementById('clearMemoryBtn');
    if (clearBtn) {
        clearBtn.addEventListener('click', handleClearMemory);
    }
}

// ── 消息发送 ──────────────────────────────────────────────────

export async function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    if (!message || state.isStreaming) return;

    input.value = '';
    input.style.height = 'auto';

    // 隐藏欢迎页
    const welcome = document.getElementById('welcomeScreen');
    if (welcome) welcome.style.display = 'none';

    appendMessage('user', message);

    // 更新会话标题
    const title = message.slice(0, 12) + (message.length > 12 ? '...' : '');
    updateSessionTitle(state.sessionId, title);

    if (state.reactEnabled) {
        await doReActChat(message);
    } else if (state.streamEnabled) {
        await doStreamChat(message);
    } else {
        await doSyncChat(message);
    }
}

function sendQuick(text) {
    const input = document.getElementById('messageInput');
    if (input) input.value = text;
    sendMessage();
}

// ── 同步模式 ──────────────────────────────────────────────────

async function doSyncChat(message) {
    setSending(true);
    const bubble = appendMessage('ai', '');
    showTypingDots(bubble);

    try {
        const data = await chatSync(state.sessionId, message);
        clearTypingDots(bubble);
        bubble.innerHTML = formatMarkdown(data.reply);
    } catch (e) {
        clearTypingDots(bubble);
        bubble.innerHTML = `<span class="error-msg">❌ 请求失败：${e.message}</span>`;
        showToast('error', '发送失败，请检查网络或服务');
    } finally {
        setSending(false);
        scrollToBottom();
    }
}

// ── 流式模式（SSE）────────────────────────────────────────────

async function doStreamChat(message) {
    setSending(true);
    state.isStreaming = true;
    const bubble = appendMessage('ai', '');
    let fullText = '';

    const eventSource = chatStream(state.sessionId, message);

    eventSource.onmessage = (e) => {
        if (e.data === '[DONE]') return;
        fullText += e.data;
        bubble.innerHTML = formatMarkdown(fullText) + '<span class="typing-cursor"></span>';
        scrollToBottom();
    };

    eventSource.addEventListener('done', () => {
        eventSource.close();
        bubble.innerHTML = formatMarkdown(fullText);
        finishStream();
    });

    eventSource.onerror = () => {
        eventSource.close();
        if (!fullText) {
            bubble.innerHTML = '<span class="error-msg">❌ 连接失败，请重试</span>';
            showToast('error', '流式连接失败');
        }
        finishStream();
    };

    function finishStream() {
        setSending(false);
        state.isStreaming = false;
        scrollToBottom();
    }
}

// ── ReAct 深度推理模式 ─────────────────────────────────────────

async function doReActChat(message) {
    setSending(true);
    const bubble = appendMessage('ai', '');
    showTypingDots(bubble);

    try {
        const data = await chatReact(state.sessionId, message);
        clearTypingDots(bubble);

        // 渲染最终答案
        let html = formatMarkdown(data.answer);

        // 若有推理步骤，折叠展示（点击可展开）
        if (data.steps && data.steps.length > 0) {
            const stepsHtml = data.steps.map(s => `
                <div class="react-step">
                    <div class="react-step-label">第 ${s.iteration} 步 · ${s.toolName}</div>
                    ${s.thought ? `<div class="react-thought">💭 ${s.thought.substring(0, 120)}${s.thought.length > 120 ? '...' : ''}</div>` : ''}
                    <div class="react-tool">🔧 ${s.toolName}(${s.toolArgs})</div>
                    ${s.observation ? `<div class="react-obs">📋 ${s.observation.substring(0, 150)}${s.observation.length > 150 ? '...' : ''}</div>` : ''}
                </div>
            `).join('');

            html = `
                <details class="react-steps-container">
                    <summary class="react-steps-summary">
                        🧠 推理过程（${data.iterations} 步 · ${data.durationMs}ms）
                    </summary>
                    <div class="react-steps">${stepsHtml}</div>
                </details>
                <div class="react-answer">${formatMarkdown(data.answer)}</div>
            `;
        }

        bubble.innerHTML = html;
    } catch (e) {
        clearTypingDots(bubble);
        bubble.innerHTML = `<span class="error-msg">❌ 推理失败：${e.message}</span>`;
        showToast('error', '深度推理失败，请重试');
    } finally {
        setSending(false);
        scrollToBottom();
    }
}

// ── 清除记忆 ──────────────────────────────────────────────────

async function handleClearMemory() {
    if (!confirm(`确认清除当前会话的所有记忆？\n会话ID: ${state.sessionId}`)) return;

    try {
        await apiClearMemory(state.sessionId);
        clearChatUI();
        showToast('success', '✅ 记忆已清除，对话重新开始');
    } catch {
        showToast('error', '清除失败，请重试');
    }
}

// ── DOM 工具 ──────────────────────────────────────────────────

function appendMessage(role, content) {
    const messages = document.getElementById('chatMessages');
    const div = document.createElement('div');
    div.className = `message ${role}`;

    const avatarHtml = role === 'ai'
        ? '<div class="avatar ai">AI</div>'
        : '<div class="avatar user">我</div>';

    div.innerHTML = `${avatarHtml}<div class="bubble">${content ? formatMarkdown(content) : ''}</div>`;
    messages.appendChild(div);
    scrollToBottom();
    return div.querySelector('.bubble');
}

function showTypingDots(bubble) {
    bubble.innerHTML = '<span class="typing-dots">●●●</span>';
    let dots = 0;
    bubble._timer = setInterval(() => {
        dots = (dots + 1) % 4;
        bubble.querySelector('.typing-dots').textContent = '●'.repeat(dots || 1);
    }, 400);
}

function clearTypingDots(bubble) {
    if (bubble._timer) clearInterval(bubble._timer);
}

function setSending(sending) {
    const btn = document.getElementById('sendBtn');
    const input = document.getElementById('messageInput');
    if (btn) { btn.disabled = sending; btn.textContent = sending ? '⏳' : '➤'; }
    if (input) input.disabled = sending;
}

function scrollToBottom() {
    const el = document.getElementById('chatMessages');
    if (el) el.scrollTop = el.scrollHeight;
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

