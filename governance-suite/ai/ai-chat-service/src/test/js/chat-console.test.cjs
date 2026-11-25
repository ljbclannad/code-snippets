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
    classList = {
        toggle: (name, force) => {
            const classes = new Set(this.className.split(/\s+/).filter(Boolean));
            const active = force ?? !classes.has(name);
            if (active) {
                classes.add(name);
            } else {
                classes.delete(name);
            }
            this.className = [...classes].join(" ");
            return active;
        }
    };
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
    focus() { this.focused = true; }
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

function openConsole(respond, patientId = "") {
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
            if (path === "/api/patient/session") return Response.json(patientId ? { customerId: patientId } : {});
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

for (const fails of [false, true]) {
    test(`同步请求${fails ? "失败" : "成功"}前禁止清空，结束后恢复清空`, async () => {
        let settle;
        const response = new Promise((resolve, reject) => {
            settle = () => fails ? reject(new Error("模拟网络故障")) : resolve(Response.json({
                success: true, message: "完整回复", conversationId: "pending-session"
            }));
        });
        const page = openConsole(() => response);
        page.controls.streamMode.checked = false;
        const sending = page.send("等待中的提问");

        assert.equal(page.controls.clearChatBtn.disabled, true);
        // 即使直接触发处理函数，也不能清除仍在等待回复的提问。
        page.controls.clearChatBtn.fire("click");
        assert.equal(page.message("user").textContent, "等待中的提问");
        settle();
        await sending;

        assert.equal(page.controls.messageList.childElementCount, 2);
        assert.equal(page.controls.clearChatBtn.disabled, false);
        assert.equal(page.controls.sendBtn.disabled, false);
        if (fails) {
            assert.match(page.message("error").textContent, /模拟网络故障/);
        } else {
            assert.equal(page.message("assistant").innerHTML, "<p>完整回复</p>\n");
        }
        page.controls.clearChatBtn.fire("click");
        assert.equal(page.controls.messageList.childElementCount, 0);
        assert.equal(page.controls.emptyState.hidden, false);
    });
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

test("流式等待期间仍可清空，并停止客户端接收", { timeout: 3000 }, async () => {
    let requestSignal;
    const page = openConsole((path, { signal }) => {
        requestSignal = signal;
        return new Response(new ReadableStream({
            start(controller) {
                signal.addEventListener("abort", () => controller.error(signal.reason), { once: true });
            }
        }));
    });
    const pending = page.send("流式清空测试");
    await Promise.resolve();
    assert.equal(page.controls.clearChatBtn.disabled, false);
    page.controls.clearChatBtn.fire("click");
    await pending;
    assert.equal(requestSignal.aborted, true);
    assert.equal(page.controls.messageList.childElementCount, 0);
    assert.equal(page.controls.emptyState.hidden, false);
    assert.equal(page.controls.sendBtn.disabled, false);
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

test("放大和退出放大保留对话、会话 ID 与输入草稿", async () => {
    const page = openConsole(() => Response.json({ success: true, conversationId: "active", message: "已有回复" }));
    const { workspace, topbar, expandChatBtn, expandChatText, streamMode, messageInput, conversationId } = page.controls;
    streamMode.checked = false;
    await page.send("第一轮");
    messageInput.value = "下一条草稿";

    expandChatBtn.fire("click");
    assert.equal(topbar.hidden, true);
    assert.match(workspace.className, /is-expanded/);
    assert.equal(expandChatText.textContent, "退出放大");

    workspace.fire("keydown", { key: "Escape" });
    assert.equal(topbar.hidden, false);
    assert.doesNotMatch(workspace.className, /is-expanded/);
    assert.equal(expandChatBtn.focused, true);
    assert.equal(messageInput.value, "下一条草稿");
    assert.equal(conversationId.value, "active");
    assert.equal(page.message("assistant").innerHTML, "<p>已有回复</p>\n");
});

test("回复期间会话历史保持可查看，暂缓切换当前会话", async () => {
    let complete;
    const page = openConsole(path => path === "/api/chat"
        ? new Promise(resolve => { complete = resolve; })
        : Response.json([{ conversationId: "history" }]));
    const { customerId, conversationList, streamMode, newChatBtn } = page.controls;
    customerId.value = "patient-demo";
    await customerId.fire("change");
    streamMode.checked = false;

    const pending = page.send("当前消息");
    const historyButton = conversationList.children[0];
    assert.equal(historyButton.disabled, true);
    assert.equal(newChatBtn.disabled, true);
    await historyButton.fire("click");
    assert.equal(page.requests.some(request => request.path.includes("/messages")), false);

    complete(Response.json({ success: true, message: "完成" }));
    await pending;
    assert.equal(conversationList.children[0].disabled, false);
    assert.equal(newChatBtn.disabled, false);
});

test("访客新建对话后获取新会话 ID，并可切回旧对话继续发送", async () => {
    const page = openConsole((path, options) => {
        const payload = JSON.parse(options.body);
        if (path === "/api/chat/stream") {
            return chunkedStream(sse({ type: "START", conversationId: "visitor-new" })
                + sse({ type: "CONTENT", content: "**新回复**" })
                + sse({ type: "DONE", conversationId: "visitor-new" }));
        }
        return Response.json({ success: true, conversationId: payload.conversationId || "visitor-old", message: "**旧回复**" });
    });
    const { conversationId, conversationList, emptyState, messageInput, messageCountText, newChatBtn, streamMode } = page.controls;
    streamMode.checked = false;
    await page.send("第一条");
    assert.equal(conversationId.value, "visitor-old");
    assert.equal(conversationList.children[0].ariaCurrent, "true");

    messageInput.value = "上一轮的草稿";
    newChatBtn.fire("click");
    assert.equal(conversationId.value, "");
    assert.equal(emptyState.hidden, false);
    assert.equal(messageCountText.textContent, "0");
    assert.equal(messageInput.value, "");
    assert.equal(messageInput.focused, true);
    assert.equal(conversationList.children[0].dataset.conversationId, "visitor-old");

    streamMode.checked = true;
    await page.send("第二条");
    assert.equal(conversationId.value, "visitor-new");
    assert.deepEqual(conversationList.children.map(item => item.dataset.conversationId), ["visitor-new", "visitor-old"]);

    await conversationList.children[1].fire("click");
    assert.equal(conversationId.value, "visitor-old");
    assert.equal(page.message("assistant").innerHTML, "<p><strong>旧回复</strong></p>\n");
    streamMode.checked = false;
    await page.send("继续旧对话");

    assert.deepEqual(page.requests.map(request => request.path), ["/api/chat", "/api/chat/stream", "/api/chat"]);
    const [first, second, resumed] = page.requests.map(request => JSON.parse(request.body));
    assert.equal(Object.hasOwn(first, "conversationId"), false);
    assert.equal(Object.hasOwn(second, "conversationId"), false);
    assert.equal(resumed.conversationId, "visitor-old");
});

test("新建对话后忽略之前的历史加载结果，仍可重新选择旧会话", async () => {
    let resolveHistory;
    const oldConversation = {
        conversationId: "patient-old",
        rounds: 1,
        messages: [{ role: "assistant", content: "已保存的回复" }]
    };
    const page = openConsole(path => {
        if (path.includes("/messages")) {
            if (!resolveHistory) {
                return new Promise(resolve => { resolveHistory = resolve; });
            }
            return Response.json(oldConversation);
        }
        return Response.json([{ conversationId: "patient-old" }]);
    });
    const { customerId, conversationId, conversationList, newChatBtn } = page.controls;
    customerId.value = "patient-demo";
    await customerId.fire("change");
    const pendingHistory = conversationList.children[0].fire("click");

    newChatBtn.fire("click");
    resolveHistory(Response.json(oldConversation));
    await pendingHistory;
    assert.equal(conversationId.value, "");
    assert.equal(page.message("assistant"), undefined);

    await conversationList.children[0].fire("click");
    assert.equal(conversationId.value, "patient-old");
    assert.equal(page.message("assistant").innerHTML, "<p>已保存的回复</p>\n");
});

test("随访登记未确认在回放后仍可原轮重试，批准与投递由进度接口提供", async () => {
    let approved = false;
    const conversationId = "follow-up-conversation";
    const turnKey = "stored-turn-key";
    const page = openConsole((path, options) => {
        if (path === "/api/chat/stream") {
            return chunkedStream(sse({ type: "START", conversationId })
                + sse({ type: "CONTENT", content: "未确认登记", followUp: { status: "UNCONFIRMED", turnKey } })
                + sse({ type: "DONE", conversationId }));
        }
        if (path === "/api/chat") {
            approved = true;
            return Response.json({ success: true, conversationId, message: "登记结果已确认",
                followUp: { status: "APPROVED", delivered: true, turnKey } });
        }
        if (path.includes("/follow-up-progress")) {
            return Response.json({ status: approved ? "APPROVED" : "NONE", delivered: approved });
        }
        if (path.includes("/messages")) {
            return Response.json({ conversationId, rounds: 1, messages: [
                { role: "USER", content: "合成样本" },
                { role: "ASSISTANT", content: "未确认登记", followUpTurnKey: turnKey, followUpConfirmed: false }
            ] });
        }
        return Response.json([{ conversationId }]);
    }, "demo-patient");
    await nextFrame();
    assert.equal(page.controls.inboxBtn.hidden, false);
    await page.send("合成样本");
    page.controls.newChatBtn.fire("click");
    await nextFrame();
    await page.controls.conversationList.children[0].fire("click");
    const button = page.controls.messageList.querySelector(".follow-up-retry");
    assert.equal(button.textContent, "重试登记");
    await button.fire("click");
    await nextFrame();

    const [first, retry] = page.requests.filter(r => r.method === "POST").map(r => JSON.parse(r.body));
    assert.deepEqual(retry, { ...first, conversationId });
    assert.equal(page.controls.messageCountText.textContent, "2");
    assert.equal(page.message("assistant").innerHTML, "<p>登记结果已确认</p>\n");
    assert.equal(button.hidden, true);
    assert.match(page.controls.followUpStatus.textContent, /已投递/);
    assert.equal(page.controls.followUpInboxBtn.hidden, false);
});
