const state = {
    eventSource: null,
    socket: null,
    markdown: ""
};

const elements = {
    promptInput: document.getElementById("promptInput"),
    transportSelect: document.getElementById("transportSelect"),
    startBtn: document.getElementById("startBtn"),
    stopBtn: document.getElementById("stopBtn"),
    statusText: document.getElementById("statusText"),
    activeTransportText: document.getElementById("activeTransportText"),
    renderModeText: document.getElementById("renderModeText"),
    preview: document.getElementById("preview"),
    eventLog: document.getElementById("eventLog")
};

function init() {
    elements.startBtn.addEventListener("click", startDemo);
    elements.stopBtn.addEventListener("click", stopDemo);
    renderMarkdown("");
    logEvent("system", "页面已加载，选择一种传输方式后即可开始。");
    checkHealth();
}

async function checkHealth() {
    try {
        const response = await fetch("/api/demo/health");
        const data = await response.json();
        elements.statusText.textContent = data.status;
    } catch (error) {
        elements.statusText.textContent = "Unavailable";
        logEvent("error", `健康检查失败: ${error.message}`);
    }
}

function startDemo() {
    stopDemo();
    state.markdown = "";
    renderMarkdown("");
    elements.eventLog.innerHTML = "";

    const transport = elements.transportSelect.value;
    const prompt = elements.promptInput.value.trim();
    elements.activeTransportText.textContent = transport.toUpperCase();
    elements.statusText.textContent = "Connecting";
    logEvent("start", `准备以 ${transport.toUpperCase()} 方式发送请求。`);

    if (transport === "sse") {
        startSse(prompt);
        return;
    }

    startWebSocket(prompt);
}

function startSse(prompt) {
    const url = `/api/demo/sse?prompt=${encodeURIComponent(prompt)}`;
    state.eventSource = new EventSource(url);

    state.eventSource.addEventListener("connected", () => {
        elements.statusText.textContent = "Streaming";
        logEvent("connected", "SSE 连接已建立。");
    });

    state.eventSource.addEventListener("delta", (event) => {
        const payload = JSON.parse(event.data);
        appendMarkdown(payload.content || "");
        logEvent("delta", `收到第 ${payload.index + 1} 个 SSE 分片。`);
    });

    state.eventSource.addEventListener("done", () => {
        elements.statusText.textContent = "Completed";
        logEvent("done", "SSE 流已结束。");
        state.eventSource.close();
        state.eventSource = null;
    });

    state.eventSource.onerror = () => {
        elements.statusText.textContent = "Error";
        logEvent("error", "SSE 连接异常结束。");
        stopDemo();
    };
}

function startWebSocket(prompt) {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    state.socket = new WebSocket(`${protocol}//${window.location.host}/ws/markdown`);

    state.socket.onopen = () => {
        elements.statusText.textContent = "Streaming";
        logEvent("connected", "WebSocket 连接已建立。");
        state.socket.send(JSON.stringify({ prompt }));
    };

    state.socket.onmessage = (event) => {
        const payload = JSON.parse(event.data);
        if (payload.type === "delta") {
            appendMarkdown(payload.content || "");
            logEvent("delta", `收到第 ${payload.index + 1} 个 WebSocket 分片。`);
            return;
        }
        if (payload.type === "done") {
            elements.statusText.textContent = "Completed";
            logEvent("done", "WebSocket 流已结束。");
            stopDemo(false);
            return;
        }
        logEvent(payload.type || "message", payload.message || "收到一条消息。");
    };

    state.socket.onerror = () => {
        elements.statusText.textContent = "Error";
        logEvent("error", "WebSocket 连接出现异常。");
    };

    state.socket.onclose = () => {
        if (elements.statusText.textContent === "Streaming") {
            elements.statusText.textContent = "Closed";
        }
    };
}

function appendMarkdown(chunk) {
    state.markdown += chunk;
    renderMarkdown(state.markdown);
}

function renderMarkdown(markdown) {
    elements.preview.innerHTML = marked.parse(markdown || "### 等待内容\n\n流式 Markdown 会渲染在这里。");
}

function stopDemo(updateStatus = true) {
    if (state.eventSource) {
        state.eventSource.close();
        state.eventSource = null;
    }

    if (state.socket) {
        state.socket.close();
        state.socket = null;
    }

    if (updateStatus) {
        elements.statusText.textContent = "Stopped";
        logEvent("stop", "已主动停止当前演示。");
    }
}

function logEvent(type, message) {
    const item = document.createElement("div");
    item.className = "event-item";
    item.innerHTML = `<strong>${escapeHtml(type)}</strong><span>${escapeHtml(message)}</span>`;
    elements.eventLog.prepend(item);
}

function escapeHtml(value) {
    return value
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

init();
