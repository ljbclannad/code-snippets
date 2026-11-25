const chatMarkdown = typeof markdownit === "function" ? markdownit({ html: false, breaks: true }) : null;
if (chatMarkdown) {
    chatMarkdown.validateLink = url => /^https?:\/\//i.test(url);
    chatMarkdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
        tokens[index].attrSet("target", "_blank");
        tokens[index].attrSet("rel", "noopener noreferrer");
        return renderer.renderToken(tokens, index, options);
    };
    chatMarkdown.renderer.rules.image = (tokens, index) => chatMarkdown.utils.escapeHtml(tokens[index].content);
    chatMarkdown.renderer.rules.table_open = () => '<div class="markdown-table" role="region" aria-label="回复表格" tabindex="0"><table>\n';
    chatMarkdown.renderer.rules.table_close = () => "</table></div>\n";
}

const state = {
    abortController: null,
    activeAssistantMessage: null,
    activeAssistantMarkdown: "",
    activeFollowUp: null,
    assistantRenderFrame: null,
    activeConversationId: "",
    connectionStatus: "idle",
    isBusy: false,
    tokenUsage: null,
    conversations: [],
    visitorConversations: new Map(),
    historySelection: 0,
    patientId: "",
    followUpRetries: new Map(),
    progressQuery: 0,
    inboxQuery: 0,
    inboxCursor: ""
};

const elements = {
    topbar: document.getElementById("topbar"),
    workspace: document.getElementById("workspace"),
    expandChatBtn: document.getElementById("expandChatBtn"),
    expandChatText: document.getElementById("expandChatText"),
    historySlot: document.getElementById("historySlot"),
    historyPanel: document.getElementById("historyPanel"),
    historyDialog: document.getElementById("historyDialog"),
    historySettingsBtn: document.getElementById("historySettingsBtn"),
    inboxBtn: document.getElementById("inboxBtn"),
    inboxDialog: document.getElementById("inboxDialog"),
    inboxStatus: document.getElementById("inboxStatus"),
    inboxList: document.getElementById("inboxList"),
    refreshInboxBtn: document.getElementById("refreshInboxBtn"),
    moreInboxBtn: document.getElementById("moreInboxBtn"),
    followUpCard: document.getElementById("followUpCard"),
    followUpStatus: document.getElementById("followUpStatus"),
    refreshFollowUpBtn: document.getElementById("refreshFollowUpBtn"),
    followUpInboxBtn: document.getElementById("followUpInboxBtn"),
    newChatBtn: document.getElementById("newChatBtn"),
    customerId: document.getElementById("customerId"),
    conversationId: document.getElementById("conversationId"),
    streamMode: document.getElementById("streamMode"),
    autoScroll: document.getElementById("autoScroll"),
    messageInput: document.getElementById("messageInput"),
    messageList: document.getElementById("messageList"),
    chatTitle: document.getElementById("chatTitle"),
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
    liveConnectionBadge: document.getElementById("liveConnectionBadge"),
    liveConnectionText: document.getElementById("liveConnectionText"),
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
    pingHealth();
    maybeLoadConversations();
    loadPatientSession();
    appendTrace("系统", "页面已加载，等待发送请求。");
}

function bindEvents() {
    elements.expandChatBtn.addEventListener("click", toggleChatExpanded);
    elements.workspace.addEventListener("keydown", event => {
        if (event.key === "Escape" && !event.isComposing && elements.topbar.hidden) {
            toggleChatExpanded();
            elements.expandChatBtn.focus();
        }
    });
    elements.historyDialog.addEventListener("close", () => {
        if (!elements.historyDialog.open) {
            elements.historySlot.appendChild(elements.historyPanel);
        }
    });
    document.querySelectorAll("[data-open-dialog]").forEach(button => {
        button.addEventListener("click", () => openWorkspaceDialog(button.dataset.openDialog));
    });
    elements.sendBtn.addEventListener("click", handleSend);
    elements.newChatBtn.addEventListener("click", startNewChat);
    elements.stopBtn.addEventListener("click", stopStreaming);
    elements.clearChatBtn.addEventListener("click", clearChat);
    elements.clearTraceBtn.addEventListener("click", () => {
        elements.traceLog.innerHTML = "";
        appendTrace("系统", "调试日志已清空。");
    });
    elements.refreshHistoryBtn.addEventListener("click", maybeLoadConversations);
    elements.refreshFollowUpBtn.addEventListener("click", refreshFollowUpProgress);
    elements.refreshInboxBtn.addEventListener("click", () => loadInbox());
    elements.moreInboxBtn.addEventListener("click", () => loadInbox(state.inboxCursor));
    elements.streamMode.addEventListener("change", updateModeText);
    elements.conversationId.addEventListener("input", updateConversationView);
    elements.conversationId.addEventListener("change", refreshFollowUpProgress);
    elements.customerId.addEventListener("change", maybeLoadConversations);
    elements.messageInput.addEventListener("keydown", (event) => {
        if ((event.metaKey || event.ctrlKey) && event.key === "Enter" && !event.isComposing) {
            event.preventDefault();
            if (!event.repeat) {
                handleSend();
            }
        }
    });

    document.querySelectorAll("[data-prompt]").forEach((button) => {
        button.addEventListener("click", () => {
            elements.messageInput.value = button.dataset.prompt || "";
            elements.messageInput.focus();
        });
    });
}

function openWorkspaceDialog(dialogId) {
    const dialog = document.getElementById(dialogId);
    if (dialog === elements.historyDialog) {
        dialog.appendChild(elements.historyPanel);
    } else if (elements.historyDialog.open) {
        elements.historyDialog.close();
    }
    dialog.showModal();
    if (dialog === elements.inboxDialog) {
        void loadInbox();
    }
}

function toggleChatExpanded() {
    const list = elements.messageList;
    const atEnd = list.scrollHeight - list.clientHeight - list.scrollTop < 2;
    const expanded = elements.workspace.classList.toggle("is-expanded");
    elements.topbar.hidden = expanded;
    elements.expandChatText.textContent = expanded ? "退出放大" : "放大对话";
    if (atEnd) {
        maybeScroll();
    }
}

function updateModeText() {
    elements.modeText.textContent = elements.streamMode.checked ? "SSE 流式" : "同步 JSON";
    updateLiveConnection();
}

function updateConversationView() {
    const value = elements.conversationId.value.trim();
    if (state.activeConversationId !== value) {
        state.progressQuery++;
        elements.followUpCard.hidden = true;
        elements.followUpInboxBtn.hidden = true;
        elements.refreshFollowUpBtn.disabled = false;
    }
    state.activeConversationId = value;
    elements.activeConversationText.textContent = value || "未创建";
    highlightActiveConversation();
}

async function loadPatientSession() {
    try {
        const response = await fetch("/api/patient/session");
        if (!response.ok) {
            return;
        }
        const { customerId } = await response.json();
        if (customerId) {
            state.patientId = customerId;
            elements.customerId.value = customerId;
            elements.customerId.readOnly = true;
            elements.inboxBtn.hidden = false;
            void maybeLoadConversations();
            void refreshFollowUpProgress();
        }
    } catch (error) {
        appendTrace("患者身份", error.message);
    }
}

async function refreshFollowUpProgress() {
    const conversation = state.activeConversationId;
    if (!state.patientId || !conversation) {
        return;
    }
    const query = ++state.progressQuery;
    elements.refreshFollowUpBtn.disabled = true;
    try {
        const response = await fetch(`/api/patient/follow-up-progress?conversationId=${encodeURIComponent(conversation)}`);
        if (!response.ok) {
            throw await responseError(response, "暂时无法查询随访进度");
        }
        const progress = await response.json();
        if (query === state.progressQuery) {
            showFollowUpProgress(progress);
        }
    } catch (error) {
        if (query === state.progressQuery) {
            elements.followUpCard.hidden = false;
            elements.followUpStatus.textContent = error.message;
            elements.followUpInboxBtn.hidden = true;
        }
    } finally {
        if (query === state.progressQuery) {
            elements.refreshFollowUpBtn.disabled = false;
        }
    }
}

function showFollowUpProgress(progress) {
    const delivered = progress.status === "APPROVED" && progress.delivered;
    const labels = {
        PENDING: "已登记，等待责任医生复核。",
        APPROVED: delivered ? "医生已批准，提醒已投递至站内收件箱。" : "医生已批准，尚未查到投递记录。",
        REJECTED: "医生已完成复核，本次未发送提醒。",
        EXPIRED: "复核任务已过期。"
    };
    elements.followUpCard.hidden = progress.status === "NONE";
    elements.followUpInboxBtn.hidden = !delivered;
    elements.followUpStatus.textContent = labels[progress.status] || "暂时无法确认随访进度。";
}

async function loadInbox(beforeId = "") {
    const query = ++state.inboxQuery;
    if (!beforeId) {
        elements.inboxList.innerHTML = "";
        state.inboxCursor = "";
        elements.moreInboxBtn.hidden = true;
    }
    elements.inboxStatus.textContent = "正在加载站内提醒…";
    elements.refreshInboxBtn.disabled = true;
    elements.moreInboxBtn.disabled = true;
    try {
        const queryString = beforeId ? `?beforeId=${encodeURIComponent(beforeId)}` : "";
        const response = await fetch(`/api/patient/follow-up-reminders${queryString}`);
        if (!response.ok) {
            throw await responseError(response, "暂时无法加载站内提醒");
        }
        const reminders = await response.json();
        if (query !== state.inboxQuery) {
            return;
        }
        for (const reminder of reminders) {
            const row = document.createElement("li");
            const message = document.createElement("p");
            message.textContent = reminder.message;
            const time = document.createElement("time");
            time.dateTime = reminder.deliveredAt;
            time.textContent = formatDateTime(reminder.deliveredAt);
            row.append(message, time);
            elements.inboxList.appendChild(row);
        }
        state.inboxCursor = reminders.length === 50 ? reminders.at(-1).receiptId : "";
        elements.moreInboxBtn.hidden = !state.inboxCursor;
        elements.inboxStatus.textContent = elements.inboxList.childElementCount ? "" : "暂无已投递的站内提醒。";
    } catch (error) {
        if (query === state.inboxQuery) {
            elements.inboxStatus.textContent = error.message;
        }
    } finally {
        if (query === state.inboxQuery) {
            elements.refreshInboxBtn.disabled = false;
            elements.moreInboxBtn.disabled = false;
        }
    }
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
    } catch (error) {
        elements.healthBadge.className = "badge error";
        elements.healthBadge.textContent = "服务异常";
        appendTrace("健康检查", `探测失败: ${error.message}`);
    }
}

async function maybeLoadConversations() {
    const customerId = elements.customerId.value.trim();
    elements.historySettingsBtn.hidden = Boolean(customerId);
    if (!customerId) {
        state.conversations = Array.from(state.visitorConversations.values()).reverse();
        renderConversationList();
        setHistoryStatus(state.conversations.length
            ? "访客历史保留在当前页面。"
            : "访客可直接开始对话。访客历史保留在当前页面。");
        return;
    }

    setHistoryStatus("正在加载会话历史...");
    try {
        const response = await fetch(`/api/chat/conversations?customerId=${encodeURIComponent(customerId)}&page=0&size=20`);
        if (!response.ok) {
            throw await responseError(response, "会话列表加载失败");
        }
        const data = await response.json();
        if (customerId !== elements.customerId.value.trim()) {
            return;
        }
        state.conversations = Array.isArray(data) ? data : [];
        renderConversationList();
        setHistoryStatus(state.conversations.length ? `已加载 ${state.conversations.length} 个会话。` : "当前用户暂无会话历史。");
        appendTrace("历史", `会话列表已刷新 · customerId=${customerId}`);
    } catch (error) {
        if (customerId !== elements.customerId.value.trim()) {
            return;
        }
        state.conversations = [];
        renderConversationList();
        setHistoryStatus(`加载失败: ${error.message}`);
        appendTrace("历史", `会话列表加载失败: ${error.message}`);
    }
}

async function responseError(response, fallback) {
    if (response.status === 401) {
        return new Error("请先登录患者账号。访客聊天请留空患者 ID。");
    }
    if (response.status === 403) {
        return new Error("无权访问此患者或会话。");
    }
    const data = await response.json().catch(() => null);
    return new Error(data?.errorMessage || data?.detail || data?.message || `${fallback}（HTTP ${response.status}）`);
}

function getPayload() {
    return {
        customerId: elements.customerId.value.trim() || undefined,
        groupId: "health-group-001",
        conversationId: elements.conversationId.value.trim() || undefined,
        requestId: Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, "0")).join(""),
        message: elements.messageInput.value.trim()
    };
}

async function handleSend() {
    if (state.isBusy) {
        return;
    }

    const payload = getPayload();
    if (!payload.message) {
        const message = "消息内容不能为空。";
        addMessage("error", "输入校验失败", message);
        appendTrace("校验", message);
        return;
    }

    const isStream = elements.streamMode.checked;
    state.historySelection++;
    setBusy(true);
    try {
        elements.messageInput.value = "";
        addMessage("user", "你", payload.message);
        updateTokenUsage(null);
        updateAgentStatus("处理中", isStream ? "等待流式响应建立" : "等待响应");
        if (isStream) {
            await sendStreamMessage(payload);
        } else {
            await sendRegularMessage(payload);
        }
    } finally {
        setBusy(false);
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
        if (!response.ok) {
            throw await responseError(response, "发送失败");
        }
        const data = await response.json();
        if (data.success === false) {
            throw new Error(data.errorMessage || "本轮对话未能完成，请稍后重试。");
        }

        applyConversationId(data.conversationId);
        const assistant = addMessage("assistant", "AI", data.message || "");
        handleFollowUpResponse(payload, data.followUp, assistant);
        rememberVisitorRound(payload, data.message || "");
        renderRecommendations(data.recommendations || []);
        updateAgentStatus("已完成", "普通响应完成");
        updateLiveConnection("complete");
        void maybeLoadConversations();
        appendTrace("响应", `普通响应完成 · conversationId=${data.conversationId || "-"}`);
    } catch (error) {
        updateAgentStatus("异常", error.message);
        updateLiveConnection("error");
        addMessage("error", "系统", error.message);
        appendTrace("错误", error.message);
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

        if (!response.ok) {
            throw await responseError(response, "流式请求失败");
        }
        if (!response.body || !(await readStreamEvents(response.body, state.abortController.signal))) {
            showStreamError("连接提前结束，本轮回复可能不完整。", "interrupted");
        }
    } catch (error) {
        if (state.abortController.signal.aborted) {
            appendTrace("中断", "已手动停止当前流式输出。");
            markAssistantMeta("已手动停止");
            updateAgentStatus(elements.activeAgentText.textContent, "已手动停止");
            updateLiveConnection("stopped");
        } else {
            showStreamError(error.message);
        }
    } finally {
        if (state.assistantRenderFrame !== null) {
            cancelAnimationFrame(state.assistantRenderFrame);
            renderActiveAssistantContent();
        }
        if (state.activeAssistantMarkdown || state.connectionStatus === "complete") {
            rememberVisitorRound(payload, state.activeAssistantMarkdown);
        }
        handleFollowUpResponse(payload, state.activeFollowUp, state.activeAssistantMessage);
        state.abortController = null;
        state.activeAssistantMessage = null;
        state.activeAssistantMarkdown = "";
        state.activeFollowUp = null;
        void maybeLoadConversations();
    }
}

async function readStreamEvents(body, signal) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    try {
        while (true) {
            const { value, done } = await reader.read().catch(error => {
                signal.throwIfAborted();
                appendTrace("流式连接", error.message);
                return { done: true };
            });
            signal.throwIfAborted();
            if (done) {
                return false;
            }
            buffer += decoder.decode(value, { stream: true });
            const { remaining, terminal } = processSseBuffer(buffer);
            if (terminal) {
                return true;
            }
            buffer = remaining;
        }
    } finally {
        // 终态已处理，取消后续读取，避免等待连接关闭或覆盖终态。
        void reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}

function processSseBuffer(buffer) {
    let cursor = 0;
    for (const separator of buffer.matchAll(/\r?\n\r?\n/g)) {
        const payload = buffer.slice(cursor, separator.index)
            .split(/\r?\n/)
            .filter(line => line.startsWith("data:"))
            .map(line => line.slice(5).replace(/^ /, ""))
            .join("\n");
        cursor = separator.index + separator[0].length;
        if (!payload) {
            continue;
        }
        let event;
        try {
            event = JSON.parse(payload);
        } catch {
            throw new Error("收到无效的流式响应，本轮回复可能不完整。");
        }
        handleStreamEvent(event);
        if (event.type === "DONE" || event.type === "ERROR") {
            return { remaining: "", terminal: true };
        }
    }
    return { remaining: buffer.slice(cursor), terminal: false };
}

function showStreamError(message, status = "error") {
    markAssistantMeta(status === "interrupted" ? "连接已中断" : "生成失败");
    updateAgentStatus("异常", message);
    updateLiveConnection(status);
    addMessage("error", "系统", message);
    appendTrace("错误", message);
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
            if (event.followUp) {
                state.activeFollowUp = event.followUp;
            }
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
            showStreamError(event.error || "流式响应失败，请稍后重试。");
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
    if (!content) {
        return;
    }
    state.activeAssistantMarkdown += content;
    if (state.assistantRenderFrame === null) {
        state.assistantRenderFrame = requestAnimationFrame(renderActiveAssistantContent);
    }
}

function renderActiveAssistantContent() {
    state.assistantRenderFrame = null;
    const contentNode = state.activeAssistantMessage.querySelector(".message-content");
    renderAssistantMarkdown(contentNode, state.activeAssistantMarkdown);
    maybeScroll();
}

function renderAssistantMarkdown(contentNode, source) {
    if (!chatMarkdown) {
        contentNode.textContent = source;
        return;
    }
    contentNode.innerHTML = chatMarkdown.render(repairSplitBold(source));
}

function handleFollowUpResponse(payload, followUp, assistant) {
    if (!followUp || !state.patientId) {
        return;
    }
    if (followUp.status === "UNCONFIRMED" && followUp.turnKey && state.activeConversationId) {
        const retry = {
            turnKey: followUp.turnKey,
            payload: { ...payload, customerId: state.patientId, conversationId: state.activeConversationId }
        };
        state.followUpRetries.set(retry.turnKey, retry);
        attachFollowUpRetry(assistant, retry);
    }
    void refreshFollowUpProgress();
}

function attachFollowUpRetry(assistant, retry) {
    const actions = document.createElement("div");
    actions.className = "follow-up-actions";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "text-btn follow-up-retry";
    button.textContent = "重试登记";
    button.disabled = state.isBusy;
    const feedback = document.createElement("span");
    feedback.role = "status";
    actions.append(button, feedback);
    assistant.querySelector(".message-body").append(actions);
    button.addEventListener("click", () => retryFollowUpRegistration(retry, assistant, button, feedback));
}

async function retryFollowUpRegistration(retry, assistant, button, feedback) {
    const { payload, turnKey } = retry;
    if (state.isBusy || payload.customerId !== state.patientId || payload.conversationId !== state.activeConversationId) {
        return;
    }
    const previousStatus = state.connectionStatus;
    setBusy(true);
    elements.stopBtn.disabled = true;
    elements.clearChatBtn.disabled = true;
    updateLiveConnection("sync");
    feedback.textContent = "正在重试登记…";
    try {
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            throw await responseError(response, "重试登记失败");
        }
        const data = await response.json();
        if (!data.success || data.conversationId !== payload.conversationId || data.followUp?.turnKey !== turnKey) {
            throw new Error("未能核实本轮登记结果，请稍后再试。");
        }
        if (data.followUp.status === "UNCONFIRMED") {
            feedback.textContent = "仍未确认登记，可稍后再试。";
        } else {
            renderAssistantMarkdown(assistant.querySelector(".message-content"), data.message || "");
            state.followUpRetries.delete(turnKey);
            button.hidden = true;
            feedback.textContent = "本轮登记结果已更新。";
            void maybeLoadConversations();
        }
        void refreshFollowUpProgress();
    } catch (error) {
        feedback.textContent = error.message;
    } finally {
        state.connectionStatus = previousStatus;
        elements.clearChatBtn.disabled = false;
        setBusy(false);
    }
}

function repairSplitBold(source) {
    if (!source.includes("****")) {
        return source;
    }

    // 流式输出可能在编号标题末尾重复闭合加粗，并把多余标记留到下一行。
    const lines = source.split(/\r\n?|\n/);
    let repaired = false;
    for (const token of chatMarkdown.parse(source, {})) {
        if (token.type !== "list_item_open") {
            continue;
        }
        const [line] = token.map;
        const heading = /^( {0,3}\d{1,9}[.)] +\*\*[^*]+\*\*)\*{2}[ \t]*$/.exec(lines[line]);
        if (heading && /^\*{2} {3,}\S/.test(lines[line + 1] ?? "")) {
            lines[line] = heading[1];
            lines[line + 1] = lines[line + 1].slice(2);
            repaired = true;
        }
    }
    return repaired ? lines.join("\n") : source;
}

function markAssistantMeta(text) {
    if (!state.activeAssistantMessage) {
        return;
    }
    const metaNode = state.activeAssistantMessage.querySelector(".message-meta");
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
    meta.textContent = options.meta || formatTime(Date.now());

    const contentNode = document.createElement("div");
    contentNode.className = "message-content";
    if (kind === "assistant" && chatMarkdown) {
        contentNode.className += " markdown-content";
        renderAssistantMarkdown(contentNode, content || "");
    } else {
        contentNode.textContent = content || "";
    }

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

function startNewChat() {
    if (state.isBusy) {
        return;
    }
    state.historySelection++;
    elements.conversationId.value = "";
    updateConversationView();
    elements.messageInput.value = "";
    resetChatView();
    if (elements.historyDialog.open) {
        elements.historyDialog.close();
    }
    elements.messageInput.focus();
    appendTrace("系统", "已开始新对话，发送后创建会话。");
    void maybeLoadConversations();
}

function clearChat() {
    if (state.isBusy && !state.abortController) {
        return;
    }
    stopStreaming();
    resetChatView();
    appendTrace("系统", "对话区域已清空。");
}

function resetChatView() {
    elements.messageList.innerHTML = "";
    elements.messageList.hidden = true;
    elements.emptyState.hidden = false;
    state.connectionStatus = "idle";
    updateTokenUsage(null);
    updateAgentStatus("未触发", "空闲");
    syncMessageCount();
    updateLiveConnection("idle");
}

function rememberVisitorRound(payload, reply) {
    const conversationId = state.activeConversationId;
    if (payload.customerId || !conversationId) {
        return;
    }

    const conversation = state.visitorConversations.get(conversationId) || {
        conversationId,
        messages: []
    };
    const timestamp = Date.now();
    conversation.messages.push({ role: "user", content: payload.message, timestamp });
    if (reply) {
        conversation.messages.push({ role: "assistant", content: reply, timestamp });
    }
    conversation.latestUserMessage = payload.message;
    conversation.latestAiResponse = reply;
    conversation.latestMessageTime = timestamp;
    state.visitorConversations.delete(conversationId);
    state.visitorConversations.set(conversationId, conversation);
}

function renderConversationList() {
    elements.conversationList.innerHTML = "";

    state.conversations.forEach((conversation) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "conversation-item";
        button.disabled = state.isBusy;
        button.dataset.conversationId = conversation.conversationId || "";
        button.innerHTML = `
            <p class="conversation-item-title">${escapeHtml(conversation.latestUserMessage || "无用户消息")}</p>
            <div class="conversation-item-preview">${escapeHtml(conversation.latestAiResponse || "暂无 AI 回复")}</div>
            <div class="conversation-item-top">
                <span>${escapeHtml(formatDateTime(conversation.latestMessageTime))}</span>
                <span>${escapeHtml(conversation.conversationId || "未命名会话")}</span>
            </div>
        `;
        button.addEventListener("click", () => loadConversationHistory(conversation.conversationId));
        elements.conversationList.appendChild(button);
    });

    highlightActiveConversation();
}

function highlightActiveConversation() {
    const activeId = state.activeConversationId;
    elements.conversationList.querySelectorAll(".conversation-item").forEach((item) => {
        const active = !!activeId && item.dataset.conversationId === activeId;
        item.classList.toggle("active", active);
        item.ariaCurrent = active ? "true" : null;
    });
}

async function loadConversationHistory(conversationId) {
    const customerId = elements.customerId.value.trim();
    if (state.isBusy || !conversationId) {
        return;
    }

    const selection = ++state.historySelection;
    const fromDrawer = elements.historyDialog.open;
    setHistoryStatus(`正在加载会话 ${conversationId} ...`);
    try {
        const data = customerId
            ? await fetchConversationHistory(conversationId, customerId)
            : state.visitorConversations.get(conversationId);
        if (selection !== state.historySelection || customerId !== elements.customerId.value.trim()) {
            return;
        }
        if (!data) {
            setHistoryStatus("这条访客会话已不在当前页面的历史中。");
            return;
        }

        applyConversationId(data.conversationId);
        renderConversationMessages(data.messages || []);
        void refreshFollowUpProgress();
        setHistoryStatus(customerId
            ? `已加载 ${data.rounds ?? 0} 轮历史会话。`
            : "访客历史保留在当前页面。");
        appendTrace("历史", `会话已切换到 ${conversationId}`);
        if (fromDrawer && elements.historyDialog.open) {
            elements.historyDialog.close();
            elements.chatTitle.focus({ preventScroll: true });
        }
    } catch (error) {
        if (selection !== state.historySelection || customerId !== elements.customerId.value.trim()) {
            return;
        }
        setHistoryStatus(`加载失败: ${error.message}`);
        addMessage("error", "系统", error.message);
        appendTrace("历史", `会话历史加载失败: ${error.message}`);
    }
}

async function fetchConversationHistory(conversationId, customerId) {
    const response = await fetch(
        `/api/chat/conversations/${encodeURIComponent(conversationId)}/messages?customerId=${encodeURIComponent(customerId)}`
    );
    if (!response.ok) {
        throw await responseError(response, "会话历史加载失败");
    }
    return response.json();
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
        const item = addMessage(kind, roleLabel, message.content || "", {
            meta: formatDateTime(message.timestamp)
        });
        const retry = state.followUpRetries.get(message.followUpTurnKey);
        if (kind === "assistant" && retry && !message.followUpConfirmed
                && retry.payload.customerId === state.patientId && retry.payload.conversationId === state.activeConversationId) {
            attachFollowUpRetry(item, retry);
        }
    });
    syncMessageCount();
    maybeScroll();
}

function setBusy(isBusy) {
    state.isBusy = isBusy;
    elements.sendBtn.disabled = isBusy;
    elements.newChatBtn.disabled = isBusy;
    elements.clearChatBtn.disabled = isBusy && !elements.streamMode.checked;
    elements.stopBtn.disabled = !isBusy || !elements.streamMode.checked;
    elements.customerId.disabled = isBusy;
    elements.conversationId.disabled = isBusy;
    elements.streamMode.disabled = isBusy;
    elements.conversationList.querySelectorAll(".conversation-item").forEach(item => {
        item.disabled = isBusy;
    });
    elements.messageList.querySelectorAll(".follow-up-retry").forEach(button => {
        button.disabled = isBusy;
    });
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
    elements.traceCountText.textContent = String(elements.traceLog.childElementCount);
    elements.traceLog.scrollTop = elements.traceLog.scrollHeight;
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
        interrupted: "连接已中断",
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
    elements.messageCountText.textContent = String(elements.messageList.childElementCount);
}

function maybeScroll() {
    if (elements.autoScroll.checked) {
        requestAnimationFrame(() => {
            const container = elements.messageList;
            container.scrollTop = container.scrollHeight;
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

function escapeHtml(text) {
    return String(text)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

init();
