const assert = require("node:assert/strict");
const { webcrypto } = require("node:crypto");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const test = require("node:test");
const { runInNewContext } = require("node:vm");

const staticPath = join(__dirname, "../../main/resources/static");
const html = readFileSync(join(staticPath, "index.html"), "utf8");
const script = readFileSync(join(staticPath, "app.js"), "utf8");

class TestElement {
    className = "";
    textContent = "";
    value = "";
    children = [];
    classList = { toggle() {} };
    dataset = {};
    listeners = {};
    metaTime = {};

    get childElementCount() { return this.children.length; }
    set innerHTML(value) { this.children = []; }

    append(...children) { this.children.push(...children); }
    appendChild(child) { this.append(child); }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    fire(name, event) { return this.listeners[name](event); }

    querySelectorAll(selector) {
        const className = selector.slice(1);
        return this.children.flatMap(child => [
            ...(child.className.split(" ").includes(className) ? [child] : []),
            ...child.querySelectorAll(selector)
        ]);
    }

    querySelector(selector) {
        if (selector === ".message-meta span:last-child") {
            return this.querySelector(".message-meta").metaTime;
        }
        return this.querySelectorAll(selector)[0];
    }
}

function openConsole(respond) {
    const controls = {};
    const requests = [];
    for (const [, tag, id] of html.matchAll(/<([^>]*\bid="([^"]+)"[^>]*)>/g)) {
        controls[id] = new TestElement();
        controls[id].value = tag.match(/\bvalue="([^"]*)"/)?.[1] || "";
        controls[id].checked = /\bchecked\b/.test(tag);
    }
    controls.autoScroll.checked = false;

    runInNewContext(script, {
        document: {
            getElementById: id => controls[id],
            createElement: () => new TestElement(),
            querySelectorAll: () => []
        },
        crypto: webcrypto,
        AbortController,
        TextDecoder,
        fetch: async (path, options = {}) => {
            if (path === "/api/chat/health") return new Response("OK");
            requests.push({ path, ...options });
            return respond(path, options);
        }
    });

    return {
        controls,
        requests,
        send(message) {
            controls.messageInput.value = message;
            return controls.sendBtn.fire("click");
        },
        message(kind) {
            const item = controls.messageList.children.findLast(child => child.dataset.kind === kind);
            return item?.querySelector(".message-content").textContent;
        }
    };
}

function sse(event, { prefix = "data:", newline = "\n" } = {}) {
    return prefix + JSON.stringify(event) + newline.repeat(2);
}

function chunkedStream(wire, { open = false, cancel } = {}) {
    const bytes = new TextEncoder().encode(wire);
    return new Response(new ReadableStream({
        start(controller) {
            for (let offset = 0; offset < bytes.length; offset += 3) {
                controller.enqueue(bytes.slice(offset, offset + 3));
            }
            if (!open) controller.close();
        },
        cancel
    }));
}

test("访客可使用普通与流式入口，后续消息沿用会话", async () => {
    const page = openConsole(path => {
        if (path === "/api/chat/stream") {
            return chunkedStream(sse({ type: "DONE", conversationId: "visitor-session" }));
        }
        return Response.json({ success: true, message: "访客回复", conversationId: "visitor-session" });
    });
    assert.equal(page.controls.customerId.value, "");
    assert.match(page.controls.historyStatus.textContent, /访客可直接开始对话/);
    await page.send("第一轮");
    page.controls.streamMode.checked = false;
    await page.send("下一轮");

    assert.deepEqual(page.requests.map(request => request.path), ["/api/chat/stream", "/api/chat"]);
    const [first, next] = page.requests.map(request => JSON.parse(request.body));
    assert.equal(Object.hasOwn(first, "customerId"), false);
    assert.equal(Object.hasOwn(next, "customerId"), false);
    assert.equal(next.conversationId, "visitor-session");
    assert.notEqual(first.requestId, next.requestId);
    assert.equal(page.message("assistant"), "访客回复");
});

test("显式填写患者 ID 时由接口确认身份，并处理无响应体的 401 和 403", async () => {
    const page = openConsole(path => {
        if (path.startsWith("/api/chat/conversations")) return new Response(null, { status: 401 });
        return new Response(null, { status: path === "/api/chat/stream" ? 401 : 403 });
    });
    page.controls.customerId.value = "patient-demo";
    await page.controls.customerId.fire("change");
    assert.match(page.controls.historyStatus.textContent, /请先登录患者账号/);

    await page.send("测试对话");
    assert.match(page.message("error"), /请先登录患者账号/);
    page.controls.streamMode.checked = false;
    await page.send("再次发送");
    assert.match(page.message("error"), /无权访问此患者或会话/);
    const posts = page.requests.filter(request => request.method === "POST");
    assert.equal(posts.length, 2);
    assert.ok(posts.every(request => JSON.parse(request.body).customerId === "patient-demo"));
});

test("兼容有无空格的 SSE，收到 DONE 后立即结束读取", { timeout: 3000 }, async () => {
    let cancelled = false;
    const page = openConsole(() => chunkedStream(
        ": keep-alive\r\n\r\n"
        + sse({ type: "START", conversationId: "stream-session" }, { newline: "\r\n" })
        + sse({ type: "CONTENT", content: "流式回复" }, { prefix: "data: " })
        + sse({ type: "DONE", tokenUsage: { totalTokens: 42 } }),
        { open: true, cancel: () => { cancelled = true; } }
    ));
    await page.send("测试消息");
    assert.equal(page.message("assistant"), "流式回复");
    assert.equal(page.controls.conversationId.value, "stream-session");
    assert.equal(page.controls.liveConnectionText.textContent, "已完成");
    assert.match(page.controls.tokenUsageText.textContent, /总计 42/);
    assert.equal(page.controls.sendBtn.disabled, false);
    assert.equal(cancelled, true);
});

test("SSE 的 ERROR 结束本轮，后续 DONE 无法覆盖错误", async () => {
    const page = openConsole(() => chunkedStream(
        sse({ type: "ERROR", error: "本轮服务异常" }) + sse({ type: "DONE" })
    ));
    await page.send("测试消息");
    assert.equal(page.message("error"), "本轮服务异常");
    assert.equal(page.controls.liveConnectionText.textContent, "异常");
});

test("已登录患者可加载对应 ID 的历史会话", async () => {
    const page = openConsole(() => Response.json([{ conversationId: "patient-session" }]));
    page.controls.customerId.value = "patient-demo";
    await page.controls.customerId.fire("change");
    assert.match(page.requests[0].path, /^\/api\/chat\/conversations\?customerId=patient-demo&/);
    assert.match(page.controls.historyStatus.textContent, /已加载 1 个会话/);
    assert.equal(page.controls.conversationList.children[0].dataset.conversationId, "patient-session");
});

test("SSE 在终态前关闭时保留部分回复并显示中断", async () => {
    const page = openConsole(() => chunkedStream(sse({ type: "CONTENT", content: "部分回复" })));
    await page.send("测试消息");
    assert.equal(page.message("assistant"), "部分回复");
    assert.match(page.message("error"), /回复可能不完整/);
    assert.equal(page.controls.liveConnectionText.textContent, "连接已中断");
    assert.equal(page.controls.sendBtn.disabled, false);
});

test("手动停止流式输出后可继续发送", { timeout: 3000 }, async () => {
    const page = openConsole((path, { signal }) => {
        if (path === "/api/chat") {
            return Response.json({ success: true, message: "下轮回复" });
        }
        return new Response(new ReadableStream({
            start(controller) {
                signal.addEventListener("abort", () => controller.error(signal.reason), { once: true });
            }
        }));
    });
    const pending = page.send("测试取消");
    await Promise.resolve();
    page.controls.stopBtn.fire("click");
    await pending;
    assert.equal(page.controls.liveConnectionText.textContent, "已停止");
    assert.equal(page.message("error"), undefined);
    assert.equal(page.controls.sendBtn.disabled, false);
    page.controls.streamMode.checked = false;
    await page.send("继续聊天");
    assert.equal(page.message("assistant"), "下轮回复");
});

test("请求未结束时快捷键无法重复发送，也保留输入草稿", async () => {
    let complete;
    const page = openConsole(() => new Promise(resolve => { complete = resolve; }));
    page.controls.streamMode.checked = false;
    const pending = page.send("正在发送的消息");
    page.controls.messageInput.value = "下一条草稿";
    for (const key of ["ctrlKey", "metaKey"]) {
        page.controls.messageInput.fire("keydown", { [key]: true, key: "Enter", preventDefault() {} });
    }
    assert.equal(page.requests.length, 1);
    assert.equal(page.controls.messageInput.value, "下一条草稿");
    complete(Response.json({ success: true, message: "完成" }));
    await pending;
    assert.equal(page.controls.sendBtn.disabled, false);
});
