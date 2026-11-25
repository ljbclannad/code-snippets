const root = "/api/doctor/follow-up-tasks";
const elements = Object.fromEntries([
    "refreshTasks", "queueStatus", "taskList", "detailTitle", "detailStatus", "detail", "taskPatient", "taskMeta",
    "taskStatus", "patientMessage", "reminder", "reviewForm", "reviewComment", "reviewStatus",
    "decision", "decisionStatus", "decisionComment"
].map(id => [id, document.getElementById(id)]));
const statuses = { PENDING: "待复核", APPROVED: "已批准", REJECTED: "已拒绝", EXPIRED: "已过期" };
const state = { activeId: null, listRequest: 0, detailRequest: 0, submitting: false };

elements.refreshTasks.addEventListener("click", refreshTasks);
elements.reviewForm.addEventListener("submit", submitReview);
void refreshTasks();

async function request(url, options) {
    const response = await fetch(url, options);
    if (response.ok) {
        return response.json();
    }
    const data = await response.json().catch(() => null);
    const messages = { 401: "请登录医生账号后刷新。", 403: "当前账号无权处理这条随访。", 404: "这条随访任务不存在。" };
    const error = new Error(messages[response.status] || data?.message || "操作暂未完成，请稍后再试。");
    error.status = response.status;
    throw error;
}

async function refreshTasks() {
    const selection = ++state.listRequest;
    elements.refreshTasks.disabled = true;
    try {
        const tasks = await request(root);
        if (selection !== state.listRequest) {
            return;
        }
        elements.taskList.replaceChildren();
        for (const task of tasks) {
            const row = document.createElement("li");
            const button = document.createElement("button");
            button.type = "button";
            button.dataset.taskId = task.id;
            const title = document.createElement("span");
            title.className = "sample";
            title.textContent = task.patientMessage;
            const summary = document.createElement("span");
            summary.className = "muted";
            summary.textContent = `${task.customerId}　${dateTime(task.createdAt)}`;
            button.append(title, summary);
            button.addEventListener("click", () => loadDetail(task.id));
            row.appendChild(button);
            elements.taskList.appendChild(row);
        }
        syncTaskButtons();
        elements.queueStatus.textContent = tasks.length ? `${tasks.length} 条待复核` : "暂无待复核的随访。";
    } catch (error) {
        if (selection === state.listRequest) {
            elements.queueStatus.textContent = error.message;
            if (error.status === 401 || error.status === 403) {
                state.detailRequest++;
                state.activeId = null;
                elements.taskList.replaceChildren();
                elements.detail.hidden = true;
                elements.detailStatus.textContent = error.message;
            }
        }
    } finally {
        if (selection === state.listRequest) {
            elements.refreshTasks.disabled = false;
        }
    }
}

function syncTaskButtons() {
    for (const button of elements.taskList.querySelectorAll("button")) {
        button.ariaCurrent = button.dataset.taskId === state.activeId ? "true" : null;
        button.disabled = state.submitting;
    }
}

async function loadDetail(id, moveFocus = true) {
    if (state.submitting) {
        return;
    }
    state.activeId = id;
    const selection = ++state.detailRequest;
    syncTaskButtons();
    elements.detail.hidden = true;
    elements.detailStatus.textContent = "正在加载随访详情…";
    try {
        const task = await request(`${root}/${encodeURIComponent(id)}`);
        if (selection === state.detailRequest) {
            renderDetail(task);
            if (moveFocus) {
                elements.detailTitle.focus();
            }
        }
    } catch (error) {
        if (selection === state.detailRequest) {
            elements.detailStatus.textContent = error.message;
        }
    }
}

function renderDetail(task) {
    elements.detailStatus.textContent = "";
    elements.detail.hidden = false;
    elements.taskPatient.textContent = `患者 ${task.customerId}`;
    elements.taskMeta.textContent = `会话 ${task.conversationId}　${dateTime(task.createdAt)}`;
    elements.taskStatus.textContent = statuses[task.status] || "状态暂不可用";
    elements.patientMessage.textContent = task.patientMessage;
    elements.reminder.textContent = task.reminder;
    elements.reviewComment.value = "";
    elements.reviewStatus.textContent = "";
    elements.reviewForm.hidden = task.status !== "PENDING";
    elements.decision.hidden = task.status === "PENDING";
    elements.decisionStatus.textContent = ({
        APPROVED: "已批准，站内提醒已投递。",
        REJECTED: "已拒绝，未发送站内提醒。",
        EXPIRED: "随访任务已过期。"
    })[task.status] || "暂时无法确认处理结果。";
    elements.decisionComment.hidden = !task.reviewComment;
    elements.decisionComment.textContent = task.reviewComment || "";
}

async function submitReview(event) {
    event.preventDefault();
    if (state.submitting || !state.activeId) {
        return;
    }
    const id = state.activeId;
    const action = event.submitter?.value;
    if (action !== "approve" && action !== "reject") {
        return;
    }
    const comment = elements.reviewComment.value.trim();
    if (action === "reject" && !comment) {
        elements.reviewStatus.textContent = "请填写拒绝理由。";
        elements.reviewComment.focus();
        return;
    }

    state.submitting = true;
    syncTaskButtons();
    for (const control of elements.reviewForm.elements) {
        control.disabled = true;
    }
    elements.reviewStatus.textContent = "正在提交复核结果…";
    let refreshDetail = false;
    try {
        const task = await request(`${root}/${encodeURIComponent(id)}/${action}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ comment })
        });
        renderDetail(task);
        elements.detailTitle.focus();
        void refreshTasks();
    } catch (error) {
        elements.reviewStatus.textContent = error.message;
        refreshDetail = error.status === 409;
    } finally {
        state.submitting = false;
        for (const control of elements.reviewForm.elements) {
            control.disabled = false;
        }
        syncTaskButtons();
    }
    if (refreshDetail) {
        await loadDetail(id, false);
        if (!elements.detail.hidden) {
            elements.detailStatus.textContent = "该随访已有处理结果，已刷新详情。";
        }
        void refreshTasks();
    }
}

function dateTime(value) {
    return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "";
}
