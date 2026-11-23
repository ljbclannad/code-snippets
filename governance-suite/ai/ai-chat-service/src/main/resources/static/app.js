const state = {
    abortController: null,
    activeAssistantMessage: null,
    activeConversationId: "",
    connectionStatus: "idle",
    isBusy: false,
    traceCount: 0,
    messageCount: 0,
    tokenUsage: null,
    conversations: []
};

const elements = {
    customerId: document.getElementById("customerId"),
    groupId: document.getElementById("groupId"),
    conversationId: document.getElementById("conversationId"),
    streamMode: document.getElementById("streamMode"),
    autoScroll: document.getElementById("autoScroll"),
    messageInput: document.getElementById("messageInput"),
    messageList: document.getElementById("messageList"),
    emptyState: document.getElementById("emptyState"),
    sendBtn: document.getElementById("sendBtn"),
    stopBtn: document.getElementById("stopBtn"),
    clearChatBtn: document.getElementById("clearChatBtn"),
    clearTraceBtn: document.getElementById("clearTraceBtn"),
    refreshHistoryBtn: document.getElementById("refreshHistoryBtn"),
    traceLog: document.getElementById("traceLog"),
    modeText: document.getElementById("modeText"),
    activeConversationText: document.getElementById("activeConversationText"),
    tokenUsageText: document.getElementById("tokenUsageText"),
    activeAgentText: document.getElementById("activeAgentText"),
    activeStageText: document.getElementById("activeStageText"),
    transportStatValue: document.getElementById("transportStatValue"),
    healthStatValue: document.getElementById("healthStatValue"),
    sessionStatValue: document.getElementById("sessionStatValue"),
    liveConnectionBadge: document.getElementById("liveConnectionBadge"),
    liveConnectionText: document.getElementById("liveConnectionText"),
    liveBusyText: document.getElementById("liveBusyText"),
    messageCountText: document.getElementById("messageCountText"),
    traceCountText: document.getElementById("traceCountText"),
    healthBadge: document.getElementById("healthBadge"),
    historyStatus: document.getElementById("historyStatus"),
    conversationList: document.getElementById("conversationList")
};

function init() {
    bindEvents();
    updateModeText();
    updateConversationView();
    updateTokenUsage(null);
    updateAgentStatus("未触发", "空闲");
    updateRuntimeStats();
    pingHealth();
    maybeLoadConversations();
    appendTrace("系统", "页面已加载，等待发送请求。");
}

function bindEvents() {
    elements.sendBtn.addEventListener("click", handleSend);
    elements.stopBtn.addEventListener("click", stopStreaming);
    elements.clearChatBtn.addEventListener("click", clearChat);
    elements.clearTraceBtn.addEventListener("click", () => {
        elements.traceLog.innerHTML = "";
        syncTraceCount();
        appendTrace("系统", "调试日志已清空。");
    });
    elements.refreshHistoryBtn.addEventListener("click", maybeLoadConversations);
    elements.streamMode.addEventListener("change", updateModeText);
    elements.conversationId.addEventListener("input", updateConversationView);
    elements.customerId.addEventListener("change", maybeLoadConversations);
    elements.customerId.addEventListener("blur", maybeLoadConversations);
    elements.messageInput.addEventListener("keydown", (event) => {
        if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
            event.preventDefault();
            handleSend();
        }
    });

    document.querySelectorAll("[data-prompt]").forEach((button) => {
        button.addEventListener("click", () => {
            elements.messageInput.value = button.dataset.prompt || "";
            elements.messageInput.focus();
        });
    });
}

function updateModeText() {
    const isStream = elements.streamMode.checked;
    elements.modeText.textContent = isStream ? "流式输出" : "普通响应";
    elements.transportStatValue.textContent = isStream ? "SSE Stream" : "JSON Sync";
    updateLiveConnection();
}

function updateConversationView() {
    const value = elements.conversationId.value.trim();
    state.activeConversationId = value;
    elements.activeConversationText.textContent = value || "未创建";
    elements.sessionStatValue.textContent = value ? abbreviateConversationId(value) : "Idle";
    highlightActiveConversation();
}

function updateTokenUsage(tokenUsage) {
    state.tokenUsage = tokenUsage;
    if (!tokenUsage || tokenUsage.totalTokens == null) {
        elements.tokenUsageText.textContent = "暂无";
        return;
    }
    elements.tokenUsageText.textContent =
        `总计 ${tokenUsage.totalTokens} · 提示 ${tokenUsage.promptTokens ?? "-"} · 输出 ${tokenUsage.completionTokens ?? "-"}`;
}

async function pingHealth() {
    try {
        const response = await fetch("/api/chat/health");
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        elements.healthBadge.className = "badge success";
        elements.healthBadge.textContent = "服务正常";
        elements.healthStatValue.textContent = "Healthy";
    } catch (error) {
        elements.healthBadge.className = "badge error";
        elements.healthBadge.textContent = "服务异常";
        elements.healthStatValue.textContent = "Degraded";
        appendTrace("健康检查", `探测失败: ${error.message}`);
    }
}

async function maybeLoadConversations() {
    const customerId = elements.customerId.value.trim();
    if (!customerId) {
        state.conversations = [];
        renderConversationList();
        setHistoryStatus("请输入用户 ID 后刷新历史。");
        return;
    }

    setHistoryStatus("正在加载会话历史...");
    try {
        const response = await fetch(`/api/chat/conversations?customerId=${encodeURIComponent(customerId)}&page=0&size=20`);
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.detail || data.message || `会话列表加载失败: HTTP ${response.status}`);
        }
        state.conversations = Array.isArray(data) ? data : [];
        renderConversationList();
        setHistoryStatus(state.conversations.length ? `已加载 ${state.conversations.length} 个会话。` : "当前用户暂无会话历史。");
        appendTrace("历史", `会话列表已刷新 · customerId=${customerId}`);
    } catch (error) {
        state.conversations = [];
        renderConversationList();
        setHistoryStatus(`加载失败: ${error.message}`);
        appendTrace("历史", `会话列表加载失败: ${error.message}`);
    }
}

function getPayload() {
    return {
        customerId: elements.customerId.value.trim(),
        groupId: elements.groupId.value.trim(),
        conversationId: elements.conversationId.value.trim() || undefined,
        requestId: Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, "0")).join(""),
        message: elements.messageInput.value.trim()
    };
}

function validatePayload(payload) {
    if (!payload.customerId || !payload.groupId || !payload.message) {
        throw new Error("用户 ID、群组 ID 和消息内容都不能为空。");
    }
}

async function handleSend() {
    const payload = getPayload();

    try {
        validatePayload(payload);
    } catch (error) {
        addMessage("error", "输入校验失败", error.message);
        appendTrace("校验", error.message);
        return;
    }

    const messageText = payload.message;
    elements.messageInput.value = "";
    addMessage("user", "你", messageText);
    state.connectionStatus = elements.streamMode.checked ? "streaming" : "sync";
    setBusy(true);
    updateTokenUsage(null);
    updateAgentStatus("处理中", "等待流式响应建立");

    if (elements.streamMode.checked) {
        await sendStreamMessage(payload);
    } else {
        await sendRegularMessage(payload);
    }
}

async function sendRegularMessage(payload) {
    appendTrace("请求", `POST /api/chat · conversationId=${payload.conversationId || "AUTO"}`);

    try {
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        if (!response.ok || data.success === false) {
            const errorMessage = data.errorMessage || `请求失败: HTTP ${response.status}`;
            throw new Error(errorMessage);
        }

        applyConversationId(data.conversationId);
        addMessage("assistant", "AI", data.message || "");
        renderRecommendations(data.recommendations || []);
        updateAgentStatus("已完成", "普通响应完成");
        updateLiveConnection("complete");
        await maybeLoadConversations();
        appendTrace("响应", `普通响应完成 · conversationId=${data.conversationId || "-"}`);
    } catch (error) {
        updateAgentStatus("异常", error.message);
        updateLiveConnection("error");
        addMessage("error", "系统", error.message);
        appendTrace("错误", error.message);
    } finally {
        setBusy(false);
    }
}

async function sendStreamMessage(payload) {
    appendTrace("请求", `POST /api/chat/stream · conversationId=${payload.conversationId || "AUTO"}`);
    state.abortController = new AbortController();
    state.activeAssistantMessage = addMessage("assistant", "AI", "", {
        meta: "流式响应建立中"
    });

    try {
        const response = await fetch("/api/chat/stream", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload),
            signal: state.abortController.signal
        });

        if (!response.ok || !response.body) {
            throw new Error(`流式请求失败: HTTP ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";

        while (true) {
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            buffer = processSseBuffer(buffer);
        }

        buffer += decoder.decode();
        processSseBuffer(buffer, true);
        appendTrace("响应", "流式连接结束。");
    } catch (error) {
        if (error.name === "AbortError") {
            appendTrace("中断", "已手动停止当前流式输出。");
            markAssistantMeta("已手动停止");
            updateAgentStatus(elements.activeAgentText.textContent, "已手动停止");
            updateLiveConnection("stopped");
        } else {
            addMessage("error", "系统", error.message);
            appendTrace("错误", error.message);
            markAssistantMeta("流式响应失败");
            updateAgentStatus("异常", error.message);
            updateLiveConnection("error");
        }
    } finally {
        state.abortController = null;
        state.activeAssistantMessage = null;
        if (elements.customerId.value.trim()) {
            await maybeLoadConversations();
        }
        setBusy(false);
    }
}

function processSseBuffer(buffer, flush = false) {
    let cursor = 0;
    while (true) {
        const separatorIndex = buffer.indexOf("\n\n", cursor);
        if (separatorIndex === -1) {
            break;
        }
        const rawEvent = buffer.slice(cursor, separatorIndex).trim();
        cursor = separatorIndex + 2;
        if (!rawEvent) {
            continue;
        }
        const dataLine = rawEvent
            .split("\n")
            .find((line) => line.startsWith("data: "));
        if (!dataLine) {
            continue;
        }
        const payload = dataLine.slice(6);
        try {
            handleStreamEvent(JSON.parse(payload));
        } catch (error) {
            appendTrace("解析", `SSE 事件解析失败: ${error.message}`);
        }
    }

    const rest = buffer.slice(cursor);
    if (flush && rest.trim().startsWith("data: ")) {
        try {
            handleStreamEvent(JSON.parse(rest.trim().slice(6)));
            return "";
        } catch (error) {
            appendTrace("解析", `尾包解析失败: ${error.message}`);
        }
    }
    return rest;
}

function handleStreamEvent(event) {
    switch (event.type) {
        case "START":
            applyConversationId(event.conversationId);
            markAssistantMeta(`会话已建立 · ${formatTime(event.timestamp)}`);
            updateLiveConnection("streaming");
            appendTrace("SSE", `START · conversationId=${event.conversationId || "-"}`);
            break;
        case "CONTENT":
            appendAssistantContent(event.content || "");
            break;
        case "STATUS":
            handleStatusEvent(event);
            break;
        case "DONE":
            if (event.conversationId) {
                applyConversationId(event.conversationId);
            }
            updateTokenUsage(event.tokenUsage || null);
            markAssistantMeta(`生成完成 · ${formatTime(event.timestamp)}`);
            updateAgentStatus(elements.activeAgentText.textContent, "已完成");
            updateLiveConnection("complete");
            appendTrace("SSE", "DONE");
            break;
        case "ERROR":
            markAssistantMeta("生成失败");
            updateAgentStatus("异常", event.error || "未知错误");
            updateLiveConnection("error");
            addMessage("error", "系统", event.error || "未知流式错误");
            appendTrace("SSE", `ERROR · ${event.error || "未知错误"}`);
            break;
        default:
            appendTrace("SSE", `未知事件类型: ${event.type}`);
    }
}

function handleStatusEvent(event) {
    const details = event.details || {};
    const agentName = resolveAgentName(details);
    if (agentName) {
        updateAgentStatus(agentName, event.status || stageLabel(event.stage));
    } else {
        updateAgentStatus(elements.activeAgentText.textContent, event.status || stageLabel(event.stage));
    }

    if (event.stage === "thinking") {
        markAssistantMeta("Agent 推理中");
        return;
    } else if (event.stage === "result") {
        markAssistantMeta("Agent 结果回传中");
    }

    appendTrace("状态", `${stageLabel(event.stage)} · ${event.status || "-"}`);
}

function appendAssistantContent(content) {
    if (!state.activeAssistantMessage) {
        state.activeAssistantMessage = addMessage("assistant", "AI", "");
    }

    const contentNode = state.activeAssistantMessage.querySelector(".message-content");
    contentNode.textContent += content;
    maybeScroll();
}

function markAssistantMeta(text) {
    if (!state.activeAssistantMessage) {
        return;
    }
    const metaNode = state.activeAssistantMessage.querySelector(".message-meta span:last-child");
    metaNode.textContent = text;
}

function updateAgentStatus(agentText, stageText) {
    if (agentText) {
        elements.activeAgentText.textContent = agentText;
    }
    if (stageText) {
        elements.activeStageText.textContent = stageText;
    }
}

function resolveAgentName(details) {
    if (!details || typeof details !== "object") {
        return "";
    }
    if (typeof details.name === "string" && details.name) {
        return details.name;
    }
    if (typeof details.agentSessionId === "string" && details.agentSessionId) {
        return `Agent 会话 ${details.agentSessionId}`;
    }
    return "";
}

function stageLabel(stage) {
    const labels = {
        agent: "Agent",
        thinking: "推理",
        thought: "思考",
        tool: "工具",
        observation: "观察",
        context: "上下文",
        approval: "审批",
        final_answer: "结论",
        result: "结果",
        done: "完成",
        error: "错误"
    };
    return labels[stage] || stage || "状态";
}

function applyConversationId(conversationId) {
    if (!conversationId) {
        return;
    }
    elements.conversationId.value = conversationId;
    updateConversationView();
}

function setHistoryStatus(text) {
    elements.historyStatus.textContent = text;
}

function addMessage(kind, roleLabel, content, options = {}) {
    elements.emptyState.hidden = true;
    elements.messageList.hidden = false;

    const item = document.createElement("article");
    item.className = `message ${kind}`;
    item.dataset.kind = kind;

    const role = document.createElement("div");
    role.className = "message-role";
    role.textContent = roleLabel;

    const body = document.createElement("div");
    body.className = "message-body";

    const meta = document.createElement("div");
    meta.className = "message-meta";
    meta.innerHTML = `<span>${roleLabel}</span><span>${options.meta || formatTime(Date.now())}</span>`;

    const contentNode = document.createElement("div");
    contentNode.className = "message-content";
    contentNode.textContent = content || "";

    body.append(meta, contentNode);
    item.append(role, body);
    elements.messageList.appendChild(item);
    syncMessageCount();
    maybeScroll();
    return item;
}

function renderRecommendations(recommendations) {
    if (!recommendations.length) {
        return;
    }

    const host = addMessage("system", "推荐", "");
    const contentNode = host.querySelector(".message-content");
    contentNode.textContent = "";

    const wrapper = document.createElement("div");
    wrapper.className = "message-recommendations";

    recommendations.forEach((item, index) => {
        const card = document.createElement("button");
        card.type = "button";
        card.className = "recommendation-card is-clickable";
        card.innerHTML = `
            <strong>推荐 ${index + 1} · 相似度 ${formatScore(item.score)}</strong>
            <div>${escapeHtml(item.userMessage || "无用户问题")}</div>
        `;
        card.addEventListener("click", () => {
            elements.messageInput.value = item.userMessage || "";
            elements.messageInput.focus();
            appendTrace("推荐", "已将推荐问题回填到输入框。");
        });
        wrapper.appendChild(card);
    });

    contentNode.appendChild(wrapper);
    maybeScroll();
}

function clearChat() {
    stopStreaming();
    elements.messageList.innerHTML = "";
    elements.messageList.hidden = true;
    elements.emptyState.hidden = false;
    state.connectionStatus = "idle";
    updateTokenUsage(null);
    updateAgentStatus("未触发", "空闲");
    syncMessageCount();
    updateLiveConnection("idle");
    appendTrace("系统", "对话区域已清空。");
}

function renderConversationList() {
    elements.conversationList.innerHTML = "";

    state.conversations.forEach((conversation) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "conversation-item";
        button.dataset.conversationId = conversation.conversationId || "";
        button.innerHTML = `
            <div class="conversation-item-top">
                <span>${escapeHtml(conversation.conversationId || "未命名会话")}</span>
                <span>${formatDateTime(conversation.latestMessageTime)}</span>
            </div>
            <p class="conversation-item-title">${escapeHtml(conversation.latestUserMessage || "无用户消息")}</p>
            <div class="conversation-item-preview">最近回复：${escapeHtml(conversation.latestAiResponse || "暂无 AI 回复")}</div>
        `;
        button.addEventListener("click", () => loadConversationHistory(conversation.conversationId));
        elements.conversationList.appendChild(button);
    });

    highlightActiveConversation();
}

function highlightActiveConversation() {
    const activeId = state.activeConversationId;
    elements.conversationList.querySelectorAll(".conversation-item").forEach((item) => {
        item.classList.toggle("active", !!activeId && item.dataset.conversationId === activeId);
    });
}

async function loadConversationHistory(conversationId) {
    const customerId = elements.customerId.value.trim();
    if (!customerId || !conversationId) {
        return;
    }

    setHistoryStatus(`正在加载会话 ${conversationId} ...`);
    try {
        const response = await fetch(
            `/api/chat/conversations/${encodeURIComponent(conversationId)}/messages?customerId=${encodeURIComponent(customerId)}`
        );
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.detail || data.message || `会话历史加载失败: HTTP ${response.status}`);
        }

        applyConversationId(data.conversationId);
        renderConversationMessages(data.messages || []);
        setHistoryStatus(`已加载 ${data.rounds ?? 0} 轮历史会话。`);
        appendTrace("历史", `会话已切换到 ${conversationId}`);
    } catch (error) {
        setHistoryStatus(`加载失败: ${error.message}`);
        addMessage("error", "系统", error.message);
        appendTrace("历史", `会话历史加载失败: ${error.message}`);
    }
}

function renderConversationMessages(messages) {
    elements.messageList.innerHTML = "";
    if (!messages.length) {
        elements.messageList.hidden = true;
        elements.emptyState.hidden = false;
        syncMessageCount();
        return;
    }

    elements.emptyState.hidden = true;
    elements.messageList.hidden = false;
    messages.forEach((message) => {
        const kind = message.role === "USER" || message.role === "user" ? "user" : "assistant";
        const roleLabel = kind === "user" ? "你" : "AI";
        addMessage(kind, roleLabel, message.content || "", {
            meta: formatDateTime(message.timestamp)
        });
    });
    syncMessageCount();
    maybeScroll();
}

function setBusy(isBusy) {
    state.isBusy = isBusy;
    elements.sendBtn.disabled = isBusy;
    elements.stopBtn.disabled = !isBusy || !elements.streamMode.checked;
    elements.customerId.disabled = isBusy;
    elements.groupId.disabled = isBusy;
    elements.conversationId.disabled = isBusy;
    elements.streamMode.disabled = isBusy;
    updateRuntimeStats();
    updateLiveConnection();
}

function stopStreaming() {
    if (state.abortController) {
        state.abortController.abort();
    }
}

function appendTrace(tag, message) {
    const line = document.createElement("div");
    line.className = "trace-entry";
    line.textContent = `[${formatTime(Date.now())}] ${tag}: ${message}`;
    elements.traceLog.appendChild(line);
    syncTraceCount();
    elements.traceLog.scrollTop = elements.traceLog.scrollHeight;
}

function updateRuntimeStats() {
    elements.liveBusyText.textContent = state.isBusy ? "Running" : "Idle";
    elements.messageCountText.textContent = String(state.messageCount);
    elements.traceCountText.textContent = String(state.traceCount);
}

function updateLiveConnection(status) {
    const resolvedStatus = status || deriveConnectionStatus();
    state.connectionStatus = resolvedStatus;
    elements.liveConnectionBadge.dataset.state = resolvedStatus;

    const labels = {
        idle: "待机中",
        sync: "同步请求",
        streaming: "流式输出中",
        complete: "已完成",
        error: "异常",
        stopped: "已停止"
    };

    elements.liveConnectionText.textContent = labels[resolvedStatus] || "待机中";
}

function deriveConnectionStatus() {
    if (state.isBusy) {
        return elements.streamMode.checked ? "streaming" : "sync";
    }
    return state.connectionStatus || "idle";
}

function syncMessageCount() {
    state.messageCount = elements.messageList.querySelectorAll(".message").length;
    updateRuntimeStats();
}

function syncTraceCount() {
    state.traceCount = elements.traceLog.childElementCount;
    updateRuntimeStats();
}

function maybeScroll() {
    if (elements.autoScroll.checked) {
        requestAnimationFrame(() => {
            const container = elements.messageList;
            container.scrollTop = container.scrollHeight;
            container.lastElementChild?.scrollIntoView({
                block: "end",
                behavior: "smooth"
            });
        });
    }
}

function formatTime(timestamp) {
    if (!timestamp) {
        return "--:--:--";
    }
    return new Date(timestamp).toLocaleTimeString("zh-CN", { hour12: false });
}

function formatDateTime(value) {
    if (!value) {
        return "--";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return date.toLocaleString("zh-CN", { hour12: false });
}

function formatScore(score) {
    if (typeof score !== "number") {
        return "-";
    }
    return score.toFixed(2);
}

function abbreviateConversationId(value) {
    if (!value) {
        return "Idle";
    }
    return value.length > 16 ? `${value.slice(0, 7)}...${value.slice(-6)}` : value;
}

function escapeHtml(text) {
    return String(text)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

init();
