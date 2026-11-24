const assert = require("node:assert/strict");
const { webcrypto } = require("node:crypto");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const test = require("node:test");
const { setImmediate: nextFrame } = require("node:timers/promises");
const { runInNewContext } = require("node:vm");

const staticPath = join(__dirname, "../../main/resources/static");
const html = readFileSync(join(staticPath, "index.html"), "utf8");
const script = readFileSync(join(staticPath, "app.js"), "utf8");
const markdownPath = html.match(/<script src="\/(vendor\/markdown-it[^"]+)"/)[1];
const markdownit = require(join(staticPath, markdownPath));

class TestElement {
    className = "";
    textContent = "";
    value = "";
    children = [];
    classList = { toggle() {} };
    dataset = {};
    listeners = {};
    markup = "";

    get childElementCount() { return this.children.length; }
    get lastElementChild() { return this.children.at(-1); }
    get innerHTML() { return this.markup; }
    set innerHTML(value) {
        this.children = [];
        this.markup = value;
    }

    append(...children) { this.children.push(...children); }
    appendChild(child) { this.append(child); }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    fire(name, event) { return this.listeners[name](event); }
    scrollIntoView() { assert.fail("自动滚动只能更新消息列表的滚动位置"); }

    querySelectorAll(selector) {
        const className = selector.slice(1);
        return this.children.flatMap(child => [
            ...(child.className.split(" ").includes(className) ? [child] : []),
            ...child.querySelectorAll(selector)
        ]);
    }

    querySelector(selector) {
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
        markdownit,
        requestAnimationFrame: setImmediate,
        cancelAnimationFrame: clearImmediate,
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
            return item?.querySelector(".message-content");
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
    assert.equal(page.controls.groupId, undefined);
    assert.match(page.controls.historyStatus.textContent, /访客可直接开始对话/);
    await page.send("第一轮");
    page.controls.streamMode.checked = false;
    await page.send("下一轮");

    assert.deepEqual(page.requests.map(request => request.path), ["/api/chat/stream", "/api/chat"]);
    const [first, next] = page.requests.map(request => JSON.parse(request.body));
    assert.equal(Object.hasOwn(first, "customerId"), false);
    assert.equal(Object.hasOwn(next, "customerId"), false);
    assert.equal(first.groupId, "health-group-001");
    assert.equal(next.groupId, "health-group-001");
    assert.equal(next.conversationId, "visitor-session");
    assert.notEqual(first.requestId, next.requestId);
    assert.equal(page.message("assistant").innerHTML, "<p>访客回复</p>\n");
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
    assert.match(page.message("error").textContent, /请先登录患者账号/);
    page.controls.streamMode.checked = false;
    await page.send("再次发送");
    assert.match(page.message("error").textContent, /无权访问此患者或会话/);
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
    assert.equal(page.message("assistant").innerHTML, "<p>流式回复</p>\n");
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
    assert.equal(page.message("error").textContent, "本轮服务异常");
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
    assert.equal(page.message("assistant").innerHTML, "<p>部分回复</p>\n");
    assert.match(page.message("error").textContent, /回复可能不完整/);
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
    assert.equal(page.message("assistant").innerHTML, "<p>下轮回复</p>\n");
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

test("自动滚动仅更新消息列表，关闭后保留列表滚动位置", async () => {
    const page = openConsole(() => Response.json({ success: true, message: "模拟回复" }));
    const { messageList, autoScroll, streamMode } = page.controls;
    streamMode.checked = false;
    messageList.scrollHeight = 720;
    messageList.scrollTop = 0;
    autoScroll.checked = true;
    await page.send("自动滚动");
    await nextFrame();
    assert.equal(messageList.scrollTop, 720);

    autoScroll.checked = false;
    messageList.scrollTop = 120;
    await page.send("保留滚动位置");
    await nextFrame();
    assert.equal(messageList.scrollTop, 120);
});

test("流式回复把跨分片的加粗、编号和子列表渲染完整", async () => {
    const page = openConsole(() => new Response(
        sse({ type: "CONTENT", content: "1. **主要是哪种睡眠问题？**" })
        + sse({ type: "CONTENT", content: "**  \n**   - 入睡困难\n   - 易醒\n\n2. **作息怎样？****  \n" })
        + sse({ type: "CONTENT", content: "**   比如：几点上床。\n\n请补充。" })
        + sse({ type: "DONE" })
    ));
    await page.send("睡眠建议");

    const reply = page.message("assistant").innerHTML;
    assert.match(reply, /<ol>\s*<li>\s*<p><strong>主要是哪种睡眠问题？<\/strong><\/p>\s*<ul>\s*<li>入睡困难<\/li>\s*<li>易醒<\/li>/);
    assert.match(reply, /<strong>作息怎样？<\/strong><br>\s*比如：几点上床。/);
    assert.match(reply, /<p>请补充。<\/p>/);
    assert.doesNotMatch(reply, /\*\*/);
});

test("同步回复保留代码原文，只生成受支持的链接和标签", async () => {
    const reply = [
        "## 回复标题",
        "> 引用和 `行内代码`",
        "",
        "```md",
        "1. **保留原文****  ",
        "**   - 代码示例",
        "```",
        "",
        "| 项目 | 内容 |",
        "| --- | --- |",
        "| 示例 | 文本 |",
        "",
        "[参考](https://example.com) [不可用](javascript:void(0)) ![替代文字](https://example.com/image.png) <b>原始标签</b>"
    ].join("\n");
    const page = openConsole(() => Response.json({ success: true, message: reply }));
    page.controls.streamMode.checked = false;
    await page.send("**用户输入**");

    const markup = page.message("assistant").innerHTML;
    assert.match(markup, /<h2>回复标题<\/h2>/);
    assert.match(markup, /<blockquote>[\s\S]+<code>行内代码<\/code>/);
    assert.match(markup, /<pre><code[^>]*>1\. \*\*保留原文\*{4} {2}\n\*\* {3}- 代码示例/);
    assert.match(markup, /<table>[\s\S]+<td>文本<\/td>/);
    assert.match(markup, /<a href="https:\/\/example\.com" target="_blank" rel="noopener noreferrer">参考<\/a>/);
    assert.match(markup, /替代文字 &lt;b&gt;原始标签&lt;\/b&gt;/);
    assert.doesNotMatch(markup, /href="javascript:|<img|<b>/);
    assert.equal(page.message("user").textContent, "**用户输入**");
});

test("历史会话沿用助手 Markdown 渲染", async () => {
    const page = openConsole(path => Response.json(path.includes("/messages") ? {
        conversationId: "patient-session",
        rounds: 1,
        messages: [{ role: "assistant", content: "**历史**\n- 一项" }]
    } : [{ conversationId: "patient-session" }]));
    page.controls.customerId.value = "patient-demo";
    await page.controls.customerId.fire("change");
    await page.controls.conversationList.children[0].fire("click");

    assert.match(page.message("assistant").innerHTML, /<strong>历史<\/strong><\/p>\s*<ul>\s*<li>一项<\/li>/);
});
