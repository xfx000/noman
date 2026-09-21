"use strict";
const demoMode = document.documentElement.dataset.demo === "true";
const request = demoMode ? window.qiqiDemoFetch : window.fetch.bind(window);
const $ = (selector) => document.querySelector(selector);
const state = {
  conversationId: crypto.randomUUID(),
  runs: new Map(),
  configured: false,
  webSearchEnabled: false,
  workspaceEnabled: false,
  executionEnabled: false,
  historyLoading: false,
  attachment: null,

  pinned: true,
  chats: [],
  activeId: null,
  lastSaved: 0,
  storageWarning: false,
};
const messages = $("#messages");
const input = $("#query");
const user = $("#user");
const scrollArea = $("#scroll-area");
const toolNames = {
  todoWrite: "更新分析计划",
  load_skill_through_path: "加载分析规范",
  list_tables: "查看业务数据表",
  describe_table: "读取表结构",
  validate_sql: "校验 SQL",
  execute_sql: "执行只读查询",
  current_time: "确认当前时间",
  generate_chart: "生成数据图表",
  reset_equipped_tools: "发现并加载工具",
  web_search: "搜索公开资料",
  analyze_file: "分析上传文件",
};
let toastTimer;

const preferences = { theme: "white", typing: true, speed: "natural" };
try {
  const saved = JSON.parse(
    localStorage.getItem("qiqi.preferences.v1") || "null",
  );
  if (["white", "sage"].includes(saved?.theme)) preferences.theme = saved.theme;
  if (typeof saved?.typing === "boolean") preferences.typing = saved.typing;
  if (["relaxed", "natural", "fast"].includes(saved?.speed))
    preferences.speed = saved.speed;
} catch {
  /* Use defaults when browser storage is unavailable. */
}
function applyPreferences(save = false) {
  document.documentElement.dataset.theme = preferences.theme;
  document
    .querySelectorAll('[name="theme"]')
    .forEach((radio) => (radio.checked = radio.value === preferences.theme));
  $("#typing-enabled").checked = preferences.typing;
  $("#typing-speed").value = preferences.speed;
  $("#typing-speed").disabled = !preferences.typing;
  if (save) {
    try {
      localStorage.setItem("qiqi.preferences.v1", JSON.stringify(preferences));
      $("#settings-feedback").textContent = "已自动保存";
    } catch {
      $("#settings-feedback").textContent =
        "浏览器存储不可用，设置仅在本次访问生效";
    }
  }
}
applyPreferences();
function closeSettings() {
  $("#settings-page").hidden = true;
  $(".workspace").inert = false;
  $(".sidebar").inert = false;
  $("#model-key").value = "";
  $("#open-settings").setAttribute("aria-expanded", "false");
  $("#open-settings").focus();
}
$("#open-settings").addEventListener("click", () => {
  $("#settings-page").hidden = false;
  $(".workspace").inert = true;
  $(".sidebar").inert = true;
  $("#open-settings").setAttribute("aria-expanded", "true");
  $("#close-settings").focus();
});
$("#close-settings").addEventListener("click", closeSettings);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !$("#settings-page").hidden) closeSettings();
});
document.querySelectorAll('[name="theme"]').forEach((radio) =>
  radio.addEventListener("change", () => {
    preferences.theme = radio.value;
    applyPreferences(true);
  }),
);
$("#typing-enabled").addEventListener("change", (event) => {
  preferences.typing = event.target.checked;
  applyPreferences(true);
});
$("#typing-speed").addEventListener("change", (event) => {
  preferences.speed = event.target.value;
  applyPreferences(true);
});

let settingsLoaded = false;
let settingsLoading = false;
const settingsLabels = {
  appearance: ["外观", "让工作台更合你的习惯。"],
  model: ["AI 模型与 Key", "管理模型服务与连接凭据。"],
  database: ["数据库", "了解数据来源，设置查询边界。"],
};
function settingsMessage(text) {
  $("#model-load-status").textContent = text;
  $("#database-load-status").textContent = text;
}
function fillServerSettings(data) {
  $("#model-provider").value = data.model.provider;
  $("#model-base-url").value = data.model.baseUrl;
  $("#model-name").value = data.model.name;
  $("#model-key-state").textContent = data.model.keyConfigured
    ? "已配置 · 不回显"
    : "未配置";
  $("#database-kind").textContent = data.database.kind;
  $("#database-location").textContent = data.database.location;
  $("#database-max-rows").value = data.database.maxRows;
  $("#database-timeout").value = data.database.timeoutSeconds;
  $("#model-fields").disabled = false;
  $("#database-fields").disabled = false;
  settingsMessage(
    data.restartRequired
      ? "已保存的配置尚未生效，请重启本地服务。"
      : "本地配置 · 仅当前机器可管理",
  );
  updateModelFields();
}
function updateModelFields() {
  $("#model-base-url").required = $("#model-provider").value === "openai";
}
$("#model-provider").addEventListener("change", updateModelFields);
async function loadServerSettings() {
  if (demoMode) {
    settingsMessage(
      "静态演示版不连接模型或数据库，不能填写或保存凭据。请在本地完整版中配置。",
    );
    $("#database-kind").textContent = "固定模拟数据";
    $("#database-location").textContent = "浏览器内演示";
    return;
  }
  if (settingsLoading || settingsLoaded) return;
  settingsLoading = true;
  settingsMessage("正在读取本地配置…");
  try {
    const result = await request("/api/settings", {
      headers: { "X-Qiqi-Settings": "1" },
      cache: "no-store",
    });
    if (!result.ok)
      throw new Error(
        "设置暂不可用，请通过本机地址访问，并确认服务以 local 配置启动。",
      );
    fillServerSettings(await result.json());
    settingsLoaded = true;
  } catch (error) {
    settingsMessage(error.message);
  } finally {
    settingsLoading = false;
  }
}
document.querySelectorAll("[data-settings-tab]").forEach((button) =>
  button.addEventListener("click", () => {
    const page = button.dataset.settingsTab;
    document
      .querySelectorAll("[data-settings-panel]")
      .forEach(
        (panel) => (panel.hidden = panel.dataset.settingsPanel !== page),
      );
    document.querySelectorAll("[data-settings-tab]").forEach((tab) => {
      const selected = tab === button;
      tab.classList.toggle("active", selected);
      if (selected) tab.setAttribute("aria-current", "page");
      else tab.removeAttribute("aria-current");
    });
    $("#settings-heading").textContent = settingsLabels[page][0];
    $("#settings-subtitle").textContent = settingsLabels[page][1];
    $(".settings-main").scrollTop = 0;
    if (page !== "appearance") loadServerSettings();
  }),
);
async function saveServerSettings(kind, payload) {
  if (demoMode) return;
  const form = $("#" + kind + "-settings-form");
  const button = form.querySelector("button[type=submit]");
  button.disabled = true;
  settingsMessage("正在保存…");
  try {
    const result = await request("/api/settings/" + kind, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Qiqi-Settings": "1" },
      body: JSON.stringify(payload),
    });
    if (!result.ok) {
      let detail;
      try {
        detail = (await result.json()).error;
      } catch {}
      throw new Error(
        result.status === 400 && detail
          ? detail
          : "保存失败，请确认本地服务可用后重试。",
      );
    }
    const data = await result.json();
    $("#model-key").value = "";
    if (kind === "model")
      $("#model-key-state").textContent = data.model.keyConfigured
        ? "已配置 · 不回显"
        : "未配置";
    settingsMessage("已保存。重启本地服务后生效，当前运行中的分析不受影响。");
  } catch (error) {
    settingsMessage(error.message);
  } finally {
    button.disabled = false;
  }
}
$("#model-settings-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveServerSettings("model", {
    provider: $("#model-provider").value,
    baseUrl: $("#model-base-url").value.trim(),
    name: $("#model-name").value.trim(),
    apiKey: $("#model-key").value,
  });
});
$("#database-settings-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveServerSettings("database", {
    maxRows: Number($("#database-max-rows").value),
    timeoutSeconds: Number($("#database-timeout").value),
  });
});

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function toast(text) {
  $("#toast").textContent = text;
  $("#toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    $("#toast").hidden = true;
  }, 2400);
}
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast("已复制");
  } catch {
    toast("复制失败，请选择内容后手动复制");
  }
}
function follow() {
  if (state.pinned) scrollArea.scrollTop = scrollArea.scrollHeight;
}
scrollArea.addEventListener(
  "scroll",
  () => {
    state.pinned =
      scrollArea.scrollHeight - scrollArea.scrollTop - scrollArea.clientHeight <
      90;
    $("#jump-latest").hidden = state.pinned || !messages.childElementCount;
  },
  { passive: true },
);
$("#jump-latest").addEventListener("click", () => {
  state.pinned = true;
  follow();
});

// Parse Markdown, then allow only report markup. Model-provided HTML never gets
// access to application controls, images, scripts, styles or event handlers.
function renderMarkdown(target, markdown) {
  const html = marked.parse(markdown, { gfm: true, breaks: false });
  target.innerHTML = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      "p",
      "br",
      "h1",
      "h2",
      "h3",
      "h4",
      "h5",
      "h6",
      "strong",
      "em",
      "del",
      "ul",
      "ol",
      "li",
      "blockquote",
      "hr",
      "pre",
      "code",
      "table",
      "thead",
      "tbody",
      "tr",
      "th",
      "td",
      "a",
    ],
    ALLOWED_ATTR: ["href", "title", "colspan", "rowspan", "start"],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  });
  target.querySelectorAll("a").forEach((link) => {
    const href = link.getAttribute("href") || "";
    if (!/^(https?:\/\/|mailto:|#)/i.test(href)) link.removeAttribute("href");
    if (/^https?:\/\//i.test(href)) {
      link.target = "_blank";
      link.rel = "noopener noreferrer";
    }
  });
  target.querySelectorAll("table").forEach((table) => {
    const wrap = element("div", "table-wrap");
    wrap.tabIndex = 0;
    wrap.setAttribute("role", "region");
    wrap.setAttribute("aria-label", "数据表格，可横向滚动");
    table.replaceWith(wrap);
    wrap.append(table);
  });
  target.querySelectorAll("pre").forEach((pre) => {
    const wrap = element("div", "code-block");
    const toolbar = element("div", "code-toolbar");
    const code = pre.querySelector("code");
    const text = code ? code.textContent : pre.textContent;
    toolbar.append(
      element(
        "span",
        "",
        /^\s*(SELECT|WITH|EXPLAIN)\b/i.test(text) ? "SQL" : "代码",
      ),
    );
    const button = element("button", "", "复制代码");
    button.type = "button";
    button.addEventListener("click", () => copy(text));
    toolbar.append(button);
    pre.replaceWith(wrap);
    wrap.append(toolbar, pre);
  });
}
function currentRun() {
  return state.runs.get(state.activeId);
}
function refreshControls() {
  const run = currentRun();
  $("#send").hidden = Boolean(run);
  $("#stop").hidden = !run;
  $("#send").disabled = !state.configured || state.historyLoading || !input.value.trim();
  // Identity changes wait for all requests, so replies cannot cross user stores.
  user.disabled = state.runs.size > 0 || !user.options.length;
  $("#new-chat").disabled = false;
  $("#status").textContent = run
    ? run.response.progress.textContent
    : activeChat()
      ? activeChat().turns.at(-1)?.status || "可以继续追问"
      : state.configured
        ? "准备好，开始探索数据"
        : "请先配置模型";
}
const historyKey = () =>
  `${demoMode ? "qiqi.demo.conversations.v1" : "qiqi.conversations.v1"}.${user.value}`;
function activeChat() {
  return state.chats.find((chat) => chat.id === state.activeId);
}
const historyVersions = new Map();
const remoteHistories = new Map();
const historyQueues = new Map();
async function loadRemoteHistory() {
  if (!state.workspaceEnabled) return;
  const username = user.value;
  state.historyLoading = true;
  refreshControls();
  try {
    const result = await request("/api/history", { headers: { "X-Qiqi-User": username } });
    if (!result.ok) throw new Error();
    const saved = await result.json();
    historyVersions.set(username, saved.revision);
    if (saved.data && user.value === username) remoteHistories.set(username, saved.data);
  } catch { toast("服务端历史读取失败，暂时显示本地记录"); }
  finally { state.historyLoading = false; refreshControls(); }
}
function syncHistory() {
  const username = user.value;
  if (!state.workspaceEnabled || !historyVersions.has(username) || state.historyLoading) return;
  const snapshot = JSON.parse(JSON.stringify({ chats: state.chats, activeId: state.activeId }));
  const queued = (historyQueues.get(username) || Promise.resolve()).then(async () => {
    if (!historyVersions.has(username)) return;
    const result = await request("/api/history", { method: "PUT", headers: { "Content-Type": "application/json", "X-Qiqi-User": username },
      body: JSON.stringify({ revision: historyVersions.get(username), data: snapshot }) });
    if (!result.ok) {
      if (result.status === 409) { historyVersions.delete(username); toast("另一个页面更新了会话，请刷新后继续；当前内容已保存在本地"); }
      else throw new Error();
      return;
    }
    historyVersions.set(username, (await result.json()).revision);
  }).catch(() => toast("服务端保存失败，当前内容已保存在本地"));
  historyQueues.set(username, queued);
}
function saveHistory() {
  if (!user.value) return;
  try {
    localStorage.setItem(
      historyKey(),
      JSON.stringify({ chats: state.chats, activeId: state.activeId }),
    );
    state.lastSaved = Date.now();
  } catch {
    if (!state.storageWarning)
      toast(state.workspaceEnabled ? "浏览器缓存不可用，正在尝试服务端保存；也可下载报告" : "浏览器存储不可用或已满，本次记录暂未保存，请下载报告。");
    state.storageWarning = true;
  }
  syncHistory();
}
function renderHistory() {
  const history = $("#history");
  history.replaceChildren();
  $("#history-count").textContent = state.chats.length;
  $("#conversation-title").textContent = activeChat()?.title || "新建分析";
  if (!state.chats.length)
    history.append(element("p", "history-empty", "还没有对话，开始一次分析吧"));
  for (const chat of [...state.chats].sort(
    (a, b) => b.updatedAt - a.updatedAt,
  )) {
    const row = element("div", "history-row");
    row.classList.toggle("selected", chat.id === state.activeId);
    const button = element("button", "history-open");
    button.title = chat.title;
    button.disabled = false;
    button.setAttribute(
      "aria-current",
      chat.id === state.activeId ? "true" : "false",
    );
    button.append(
      element("span", "history-title", chat.title),
      element(
        "span",
        "history-date",
        new Date(chat.updatedAt).toLocaleDateString("zh-CN", {
          month: "numeric",
          day: "numeric",
        }) + ` · ${chat.turns.length} 轮对话`,
      ),
    );
    button.addEventListener("click", () => openConversation(chat.id));
    if (state.runs.has(chat.id)) {
      row.classList.add("running");
      button.querySelector(".history-date").textContent = "正在分析…";
    } else if (chat.unread) {
      row.classList.add("unread");
      button.querySelector(".history-date").textContent = "新回复 · 点击查看";
    }
    const remove = element("button", "history-delete");
    remove.innerHTML =
      '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6"/></svg>';
    remove.title = "删除对话";
    remove.setAttribute("aria-label", `删除对话：${chat.title}`);
    remove.addEventListener("click", () => requestDelete(chat.id));
    row.append(button, remove);
    history.append(row);
  }
}
let deletionId = null;
function requestDelete(id) {
  const chat = state.chats.find((item) => item.id === id);
  if (!chat) return;
  deletionId = id;
  $("#delete-title").textContent = chat.title;
  $("#delete-description").textContent = state.runs.has(id)
    ? "这条分析仍在生成。删除会同时停止生成，并移除本地对话记录。"
    : "删除后，这条对话及其分析记录将从当前浏览器移除。";
  $("#confirm-delete").textContent = state.runs.has(id)
    ? "停止并删除"
    : "删除对话";
  $("#delete-dialog").showModal();
}
$("#cancel-delete").addEventListener("click", () =>
  $("#delete-dialog").close(),
);
$("#confirm-delete").addEventListener("click", () => {
  const id = deletionId;
  stopRun(state.runs.get(id));
  state.chats = state.chats.filter((chat) => chat.id !== id);
  if (state.activeId === id) resetConversation();
  saveHistory();
  renderHistory();
  $("#delete-dialog").close();
  toast("对话已删除");
});
function loadHistory() {
  state.chats = [];
  state.activeId = null;
  try {
    const saved = remoteHistories.has(user.value) ? remoteHistories.get(user.value)
      : JSON.parse(localStorage.getItem(historyKey()) || "null");
    remoteHistories.delete(user.value);
    if (Array.isArray(saved?.chats)) {
      state.chats = saved.chats.filter(
        (chat) =>
          typeof chat.id === "string" &&
          typeof chat.title === "string" &&
          Array.isArray(chat.turns),
      );
      for (const chat of state.chats) {
        // Refresh during a stream keeps the partial response, but does not reuse
        // a server turn that may still be running.
        if (chat.turns.some((turn) => turn.pending)) {
          if (!state.workspaceEnabled) chat.runtimeId = crypto.randomUUID();
          for (const turn of chat.turns.filter((turn) => turn.pending)) {
            turn.pending = false;
            turn.recoverExecution = true;
            turn.status = "生成已中断 · 已保留部分内容";
          }
        }
      }
      const chat = state.chats.find((item) => item.id === saved.activeId);
      if (chat) {
        openConversation(chat.id);
        return;
      }
    }
  } catch {
    toast("历史记录暂时无法读取");
  }
  resetConversation();
}
function openConversation(id) {
  if (!$("#settings-page").hidden) closeSettings();
  const chat = state.chats.find((item) => item.id === id);
  if (!chat) return;
  if (activeChat()) activeChat().draft = input.value;
  chat.unread = false;
  state.activeId = chat.id;
  state.conversationId = chat.runtimeId;
  messages.replaceChildren();
  input.value = chat.draft || "";
  resizeInput();
  $("#welcome").hidden = true;
  for (const turn of chat.turns) {
    messages.append(element("article", "message user", turn.query));
    const live = state.runs.get(chat.id);
    if (live?.response.savedTurn === turn) {
      messages.append(live.response.card);
      continue;
    }
    const response = createResponse(turn.query, turn.time, chat.runtimeId);
    response.text = turn.text || "";
    render(response);
    response.progress.classList.remove("busy");
    response.progress.textContent = turn.status || "分析完成";
    response.error.textContent = turn.error || "";
    response.error.hidden = !turn.error;
    response.savedTurn = turn;
    if (!turn.execution) for (const step of turn.steps || [])
      response.steps.append(
        element("li", step.failed ? "failed" : "", step.text),
      );
    response.trace.hidden = !response.steps.children.length;
    response.summary.textContent = `分析过程 · ${response.steps.children.length} 次工具调用`;
    for (const raw of turn.evidence || []) addEvidence(response, raw);
    for (const chart of turn.charts || []) addChart(response, chart);
    for (const message of turn.chartErrors || []) addChartError(response, message);
    for (const source of turn.webSources || []) addWebSource(response, source);
    if (turn.diagnostics) showDiagnostics(response, turn.diagnostics);
    if (turn.execution) applyExecution(response, turn.execution, true);
    if (state.executionEnabled && turn.diagnostics?.runId && (turn.recoverExecution || turn.diagnostics.status === "RUNNING"))
      recoverExecution(response);

    response.actions.hidden = !response.text;
  }
  state.pinned = true;
  follow();
  saveHistory();
  renderHistory();
  refreshControls();
}
function resetConversation() {
  if (activeChat()) activeChat().draft = input.value;
  state.activeId = null;
  state.conversationId = crypto.randomUUID();
  messages.replaceChildren();
  input.value = "";
  input.style.height = "";
  state.attachment = null;
  $("#file-status").textContent = "";
  $("#welcome").hidden = false;
  $("#jump-latest").hidden = true;
  state.pinned = true;
  scrollArea.scrollTop = 0;
  $("#status").textContent = state.configured
    ? "准备好，开始探索数据"
    : "请先配置模型";
  saveHistory();
  renderHistory();
  refreshControls();
  input.focus();
}
function captureResponse(response, force = false) {
  if (!response.savedTurn) return;
  Object.assign(response.savedTurn, {
    text: response.text,
    status: response.progress.textContent,
    error: response.error.hidden ? "" : response.error.textContent,
    steps: response.execution ? [] : [...response.steps.children].map((step) => ({
      text: step.textContent,
      failed: step.classList.contains("failed"),
    })),
    evidence: [...response.results.values()],
    charts: [...response.charts.values()],
    chartErrors: [...response.chartErrors],
    webSources: [...response.webSources.values()],
    diagnostics: response.diagnostics,
    execution: response.execution ? { ...response.execution, text: "" } : null,
  });
  if (response.username === user.value && (force || Date.now() - state.lastSaved > 1000)) saveHistory();
}
window.addEventListener("pagehide", () => {
  for (const run of state.runs.values()) captureResponse(run.response);
  saveHistory();
});
function updateScope() {
  const option = user.selectedOptions[0];
  $("#scope").textContent =
    option?.dataset.scope === "ALL" ? "全部数据" : "本部门数据";
}
async function loadMeta() {
  const response = await request("/api/meta");
  if (!response.ok) throw new Error("服务暂不可用，请稍后刷新页面");
  const meta = await response.json();
  for (const item of meta.demoUsers) {
    const option = element("option", "", item.displayName);
    option.value = item.username;
    option.dataset.scope = item.dataScope;
    user.append(option);
  }
  state.configured = Boolean(
    meta.modelConfigured &&
    user.options.length &&
    window.marked &&
    window.DOMPurify,
  );
  $(".connection").classList.add("ready");
  $("#connection-label").textContent = demoMode
    ? "演示模式 · 模拟数据"
    : "示例数据库已连接";
  if (demoMode) {
    $("#notice").textContent =
      "交互演示：回答为预设示例，不执行真实查询；输入仅保存在当前浏览器。";
    $("#notice").hidden = false;
  }
  if (!state.configured) {
    $("#notice").textContent =
      !window.marked || !window.DOMPurify
        ? "页面资源加载失败，请刷新后重试。"
        : "模型尚未配置，暂时无法发起分析。请完成本地模型配置后刷新页面。";
    $("#notice").hidden = false;
  }
  state.webSearchEnabled = Boolean(meta.webSearchEnabled);
  $("#online-search").disabled = !state.webSearchEnabled;
  $("#online-hint").textContent = state.webSearchEnabled
    ? "仅本次提问 · 搜索关键词将发送给 Tavily"
    : "搜索服务未配置";
  updateScope();
  state.workspaceEnabled = Boolean(meta.workspaceEnabled);
  state.executionEnabled = Boolean(meta.executionEnabled);
  await loadRemoteHistory();
  loadHistory();
  refreshControls();
}
function createResponse(query, timestamp = new Date().toISOString(), conversationId = state.conversationId) {
  const card = element("article", "message assistant");
  const heading = element("div", "assistant-heading");
  const time = element(
    "time",
    "",
    new Date(timestamp).toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
    }),
  );
  time.dateTime = timestamp;
  heading.append(
    element("span", "mini-mark", "Q"),
    element("strong", "", "Qiqi"),
    element("span", "report-label", "数据分析"),
    time,
  );
  const trace = element("details", "tool-trace");
  trace.hidden = true;
  trace.open = true;
  const summary = element("summary", "", "分析过程");
  const steps = element("ol");
  trace.append(summary, steps);
  const todoArea = element("section", "todo-panel");
  todoArea.hidden = true;
  todoArea.setAttribute("aria-label", "分析计划");
  const report = element("div", "report");
  const progress = element("div", "response-status busy", "正在理解你的问题…");
  const error = element("div", "response-error");
  error.hidden = true;
  error.setAttribute("role", "alert");
  const evidence = element("div", "query-evidence");
  const chartArea = element("div", "chart-results");
  const sourceArea = element("section", "web-sources");
  sourceArea.hidden = true;
  sourceArea.append(element("strong", "", "网络来源"));
  const diagnosticsArea = element("details", "run-diagnostics");
  diagnosticsArea.hidden = true;
  const diagnosticsSummary = element("summary", "", "运行详情");
  const diagnosticsBody = element("div", "diagnostics-body");
  diagnosticsArea.append(diagnosticsSummary, diagnosticsBody);
  const actions = element("div", "report-actions");
  actions.hidden = true;
  const response = {
    card,
    report,
    progress,
    error,
    trace,
    summary,
    steps,
    todoArea,
    execution: null,
    toolDetails: new Map(),
    actions,
    evidence,
    chartArea,
    sourceArea,
    diagnosticsArea,
    diagnosticsSummary,
    diagnosticsBody,
    diagnostics: null,
    username: user.value,
    conversationId,
    webSources: new Map(),
    charts: new Map(),
    chartErrors: new Set(),
    chartToolText: new Map(),
    query,
    text: "",
    tools: new Map(),
    results: new Map(),
    replyId: null,
    renderTimer: null,
    visibleLength: 0,
    done: false,
    runEnded: false,
    failed: false,
    queries: new Set(),
  };
  const copyButton = element("button", "", "复制 Markdown");
  copyButton.addEventListener("click", async () => {
    try { await copy(await portableReport(response)); } catch { toast("图表读取失败，请稍后重试导出"); }
  });
  const exportButton = element("button", "", "下载报告");
  exportButton.addEventListener("click", async () => {
    let markdown;
    try { markdown = await portableReport(response); } catch { toast("图表读取失败，请稍后重试导出"); return; }
    const url = URL.createObjectURL(
      new Blob([markdown], { type: "text/markdown;charset=utf-8" }),
    );
    const link = element("a");
    link.href = url;
    link.download = `Qiqi-分析报告-${new Date().toISOString().slice(0, 10)}.md`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  actions.append(copyButton, exportButton);
  card.append(heading, todoArea, trace, report, chartArea, progress, error, evidence, sourceArea, diagnosticsArea, actions);
  messages.append(card);
  return response;
}
function paintResponse(response, text) {
  if (text) renderMarkdown(response.report, text);
  captureResponse(response);
  if (response.card.isConnected) follow();
}
function render(response) {
  clearTimeout(response.renderTimer);
  response.renderTimer = null;
  response.visibleLength = response.text.length;
  response.report.classList.remove("typing");
  paintResponse(response, response.text);
}
function scheduleRender(response) {
  if (response.renderTimer) return;
  response.renderTimer = setTimeout(() => {
    response.renderTimer = null;
    const remaining = Array.from(response.text.slice(response.visibleLength));
    const animate =
      preferences.typing &&
      !window.matchMedia("(prefers-reduced-motion: reduce)").matches &&
      !document.hidden;
    const base = { relaxed: 1, natural: 2, fast: 4 }[preferences.speed];
    // Catch up gently when the gateway delivers a large burst. Full received
    // text is saved independently so interruption never drops buffered output.
    const count = animate
      ? Math.max(base, Math.ceil(remaining.length / 65))
      : remaining.length;
    response.visibleLength += remaining.slice(0, count).join("").length;
    response.report.classList.toggle("typing", animate);
    paintResponse(response, response.text.slice(0, response.visibleLength));
    if (response.visibleLength < response.text.length) scheduleRender(response);
  }, 32);
}
function finishTyping(response, signal) {
  return new Promise((resolve) => {
    let timer;
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const check = () => {
      if (signal.aborted || response.visibleLength >= response.text.length)
        finish();
      else {
        scheduleRender(response);
        timer = setTimeout(check, 32);
      }
    };
    signal.addEventListener("abort", finish, { once: true });
    check();
  });
}
function addEvidence(response, raw) {
  try {
    let result = JSON.parse(raw);
    if (typeof result === "string") result = JSON.parse(result);
    if (
      !result.queryId ||
      (!result.executedSql && !Array.isArray(result.columns)) ||
      response.queries.has(result.queryId)
    )
      return;
    response.queries.add(result.queryId);
    const details = element("details", "evidence-item");
    details.append(
      element(
        "summary",
        "",
        `查询证据 · ${result.rowCount} 行 · ${result.durationMs} ms`,
      ),
    );
    const id = element("div", "evidence-id", `queryId: ${result.queryId}`);
    const sql = element("div", "report");
    // Render SQL as text nodes so neither SQL nor gateway data can inject markup.
    const block = element("div", "code-block");
    const toolbar = element("div", "code-toolbar");
    const button = element("button", "", result.displayRedacted ? "复制脱敏 SQL" : "复制 SQL");
    button.addEventListener("click", () => copy(result.executedSql));
    toolbar.append(element("span", "", result.displayRedacted ? "执行 SQL · 敏感值已隐藏" : "实际执行 SQL"), button);
    const pre = element("pre");
    pre.append(element("code", "", result.executedSql));
    block.append(toolbar, pre);
    sql.append(block);
    if (result.executedSql) details.append(id, sql);
    else details.append(id, element("p", "", result.source?.operation === "preview" ? "文件预览 · 仅展示前 20 行" : "文件分析结果 · 使用完整文件进行统计"));
    if (result.source?.type === "csv") details.append(element("p", "", `来源：${result.source.name} · ${result.source.totalRows} 行 · ${result.source.operation}${result.source.valueColumn ? `(${result.source.valueColumn})` : ""}${result.source.groupBy ? ` · 按 ${result.source.groupBy} 分组` : ""}`));
    if (state.workspaceEnabled) {
      const download = element("button", "", result.truncated ? "导出预览 CSV（非完整结果）" : "导出 CSV");
      download.addEventListener("click", () => downloadProtected(`/api/evidence/${encodeURIComponent(result.queryId)}/csv?conversationId=${encodeURIComponent(response.conversationId)}`, response.username, "Qiqi-result.csv"));
      details.append(download);
    }
    response.evidence.append(details);
  } catch {
    /* Tool errors and partial JSON do not create evidence. */
  }
}
function chartSource(value) {
  if (typeof value !== "string" || value.length > 2_000_022) return null;
  if (/^\/api\/charts\/[a-f0-9-]{36}$/.test(value)) return value;
  if (/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) return value;
  try {
    const url = new URL(value);
    if (["http:", "https:"].includes(url.protocol) && !url.username && !url.password)
      return url.href;
  } catch { /* Invalid chart sources never become links or images. */ }
  return null;
}
function addChart(response, chart) {
  const source = chartSource(chart?.source);
  if (!source || typeof chart.id !== "string" || typeof chart.queryId !== "string"
      || typeof chart.title !== "string" || response.charts.has(chart.id)) return;
  const safe = { id: chart.id, queryId: chart.queryId, title: chart.title, type: chart.type, source };
  response.charts.set(safe.id, safe);
  const figure = element("figure", "chart-card");
  const caption = element("figcaption", "chart-caption");
  caption.append(element("strong", "", safe.title));
  const link = element("a", "chart-download", source.startsWith("data:") ? "下载 PNG" : "打开原图");
  link.href = source;
  link.rel = "noopener noreferrer";
  if (source.startsWith("data:")) link.download = "Qiqi-chart.png";
  else link.target = "_blank";
  caption.append(link);
  const image = element("img", "chart-image");
  image.alt = safe.title;
  image.loading = "lazy";
  image.referrerPolicy = "no-referrer";
  const failure = element("p", "chart-error", "图片加载失败，原图链接可能已过期，请重新生成图表。");
  failure.hidden = true;
  image.addEventListener("error", () => { image.hidden = true; failure.hidden = false; });
  if (source.startsWith("/api/charts/")) {
    link.textContent = "下载 PNG";
    link.href = "#";
    link.removeAttribute("target");
    link.addEventListener("click", event => { event.preventDefault(); downloadProtected(source, response.username, "Qiqi-chart.png"); });
    request(source, { headers: { "X-Qiqi-User": response.username } }).then(async result => {
      if (!result.ok) throw new Error();
      const url = URL.createObjectURL(await result.blob());
      image.addEventListener("load", () => URL.revokeObjectURL(url), { once: true });
      image.src = url;
    }).catch(() => { image.hidden = true; failure.hidden = false; });
  } else image.src = source;
  figure.append(caption, image, failure, element("p", "chart-evidence", `查询证据 · ${safe.queryId}`));
  response.chartArea.append(figure);
}
function addChartError(response, message) {
  if (!message || response.chartErrors.has(message)) return;
  response.chartErrors.add(message);
  const error = element("p", "chart-error", message);
  error.setAttribute("role", "status");
  response.chartArea.append(error);
}
async function downloadProtected(url, username, filename) {
  try {
    const result = await request(url, { headers: { "X-Qiqi-User": username } });
    if (!result.ok) throw new Error();
    const objectUrl = URL.createObjectURL(await result.blob());
    const link = element("a"); link.href = objectUrl; link.download = filename; link.click();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  } catch { toast("下载失败，请检查当前身份与服务状态"); }
}
async function stopRun(run) {
  if (!run) return;
  const id = run.response.diagnostics?.runId;
  if (state.workspaceEnabled && id) {
    try { await request(`/api/runs/${encodeURIComponent(id)}/cancel`, { method: "POST", headers: { "X-Qiqi-User": run.response.username } }); }
    catch { /* Disconnect still cancels the server subscription. */ }
  }
  run.controller.abort();
}
function addWebSource(response, source) {
  if (!source || typeof source.url !== "string" || source.url.length > 2048) return;
  const url = chartSource(source.url);
  if (!url || !/^https?:/.test(url) || response.webSources.has(url)) return;
  const safe = { title: String(source.title || url).slice(0, 200), url };
  response.webSources.set(url, safe);
  response.sourceArea.hidden = false;
  const link = element("a", "", safe.title);
  link.href = url;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  response.sourceArea.append(link);
}
function showDiagnostics(response, run) {
  if (!run || typeof run.runId !== "string" || !/^[a-f0-9-]{36}$/.test(run.runId)) return;
  response.diagnostics = run;
  response.diagnosticsArea.hidden = false;
  const labels = { RUNNING: "进行中", SUCCEEDED: "完成", PARTIAL: "完成 · 含工具失败", FAILED: "失败", CANCELLED: "已取消", INCOMPLETE: "未完成" };
  const duration = Math.max(0, Number(run.durationMs) || 0);
  response.diagnosticsSummary.textContent = `运行详情 · ${labels[run.status] || "未知状态"} · ${(duration / 1000).toFixed(1)} 秒`;
  const id = element("p", "run-id", `运行编号：${run.runId}`);
  response.diagnosticsBody.replaceChildren(id);
  if (run.errorCode) response.diagnosticsBody.append(element("p", "", `故障代码：${run.errorCode}`));
  if (run.usage) response.diagnosticsBody.append(element("p", "", `Token：输入 ${run.usage.inputTokens} / 输出 ${run.usage.outputTokens} / 缓存 ${run.usage.cachedTokens}`));
  else response.diagnosticsBody.append(element("p", "", "Token：模型未返回用量"));
  const operations = element("ul");
  for (const op of (run.operations || []).slice(0, 250)) {
    const name = op.kind === "model" ? "模型调用" : toolNames[op.name] || op.name;
    const line = element("li", op.status === "FAILED" ? "failed" : "",
      `${name} · ${labels[op.status] || op.status} · ${(Math.max(0, Number(op.durationMs) || 0) / 1000).toFixed(2)} 秒${op.errorCode ? ` · ${op.errorCode}` : ""}`);
    operations.append(line);
  }
  response.diagnosticsBody.append(operations);
  const refresh = element("button", "", "刷新运行状态");
  refresh.type = "button";
  refresh.addEventListener("click", async () => {
    refresh.disabled = true;
    try {
      const result = await request(`/api/runs/${encodeURIComponent(run.runId)}`, { headers: { "X-Qiqi-User": response.username } });
      if (!result.ok) throw new Error("运行记录不可用或已过期");
      showDiagnostics(response, await result.json());
      if (state.executionEnabled) await recoverExecution(response);
      captureResponse(response, true);
    } catch { toast("运行记录不可用或已过期"); }
    finally { refresh.disabled = false; }
  });
  response.diagnosticsBody.append(refresh);
}
async function portableReport(response) {
  let markdown = reportMarkdown(response);
  for (const chart of response.charts.values()) {
    if (!chart.source.startsWith("/api/charts/")) continue;
    const result = await request(chart.source, { headers: { "X-Qiqi-User": response.username } });
    if (!result.ok) throw new Error();
    const blob = await result.blob();
    const data = await new Promise((resolve, reject) => {
      const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(blob);
    });
    markdown = markdown.replaceAll(chart.source, data);
  }
  return markdown;
}
function reportMarkdown(response) {
  return response.text + [...response.charts.values()].map(chart =>
    `\n\n![${chart.title.replace(/[\[\]\r\n]/g, " ")}](${chart.source.replace(/\(/g, "%28").replace(/\)/g, "%29")})\n\n查询证据：${chart.queryId}`,
  ).join("") + (response.webSources.size ? "\n\n网络来源：\n" + [...response.webSources.values()].map(source =>
    `- [${source.title.replace(/[\[\]\r\n]/g, " ")}](${source.url.replace(/\(/g, "%28").replace(/\)/g, "%29")})`).join("\n") : "");
}
const executionLabels = { QUEUED: "准备参数", RUNNING: "执行中", SUCCEEDED: "完成", FAILED: "未成功", CANCELLED: "已停止", INCOMPLETE: "未完成" };
function applyExecution(response, execution, restore = false) {
  if (!execution || execution.version !== 1 || execution.conversationId !== response.conversationId
      || !Array.isArray(execution.tools) || !Array.isArray(execution.todos)
      || (response.diagnostics?.runId && response.diagnostics.runId !== execution.runId)) return;
  if (response.execution?.runId === execution.runId && response.execution.revision > execution.revision) return;
  response.execution = execution;
  response.trace.hidden = !execution.tools.length;
  const ids = new Set(execution.tools.map(tool => tool.id));
  for (const [id, view] of response.toolDetails) if (!ids.has(id)) {
    view.item.remove(); response.toolDetails.delete(id); response.tools.delete(id);
  }
  for (const tool of execution.tools.slice(0, 128)) {
    if (typeof tool.id !== "string") continue;
    let view = response.toolDetails.get(tool.id);
    if (!view) {
      const item = element("li", "tool-step");
      const details = element("details", "tool-detail");
      const title = element("summary");
      const input = element("pre", "tool-payload");
      const output = element("pre", "tool-payload");
      const inputLabel = element("div", "tool-payload-label", "调用参数 · 已脱敏");
      const outputLabel = element("div", "tool-payload-label", "返回结果 · 摘要");
      details.append(title, inputLabel, input, outputLabel, output);
      item.append(details); response.steps.append(item);
      view = { item, title, input, output };
      response.toolDetails.set(tool.id, view); response.tools.set(tool.id, item);
    }
    const duration = (Math.max(0, Number(tool.durationMs) || 0) / 1000).toFixed(2);
    view.title.textContent = `${toolNames[tool.name] || tool.name} · ${executionLabels[tool.status] || tool.status} · ${duration} 秒`;
    view.item.classList.toggle("failed", ["FAILED", "INCOMPLETE", "CANCELLED"].includes(tool.status));
    view.item.dataset.status = tool.status;
    view.input.textContent = tool.arguments || (["QUEUED", "RUNNING"].includes(tool.status) ? "正在接收参数…" : "无参数");
    view.output.textContent = tool.result || (["QUEUED", "RUNNING"].includes(tool.status) ? "等待工具返回…" : "未返回文本");
  }
  response.summary.textContent = `分析过程 · ${execution.tools.length} 次工具调用${execution.truncated ? " · 部分记录已省略" : ""}`;
  response.todoArea.hidden = !execution.todos.length;
  const completed = execution.todos.filter(todo => todo.status === "completed").length;
  const title = element("strong", "", `分析计划 · ${completed}/${execution.todos.length}`);
  const list = element("ol", "todo-list");
  for (const todo of execution.todos.slice(0, 20)) {
    const interrupted = todo.status === "in_progress" && execution.status !== "RUNNING";
    const label = todo.status === "completed" ? "已完成" : interrupted ? "未完成" : todo.status === "in_progress" ? "进行中" : "待处理";
    const row = element("li", `todo-item ${interrupted ? "interrupted" : todo.status}`);
    row.append(element("span", "todo-state", label), element("span", "", todo.content));
    list.append(row);
  }
  response.todoArea.replaceChildren(title, list);
  for (const evidence of execution.evidence || []) {
    const raw = JSON.stringify(evidence);
    response.results.set(evidence.queryId, raw); addEvidence(response, raw);
  }
  for (const chart of execution.charts || []) addChart(response, chart);
  for (const source of execution.sources || []) addWebSource(response, source);
  if (execution.status === "RUNNING") response.progress.textContent = execution.progress || "正在分析…";
  else response.progress.classList.remove("busy");
  if (restore) {
    // Never re-run a model to restore a page: the server journal is authoritative.
    response.text = execution.text || response.text; render(response);
    response.progress.textContent = execution.progress;
    response.actions.hidden = !response.text;
    if (["FAILED", "INCOMPLETE"].includes(execution.status)) {
      response.error.hidden = false;
      response.error.textContent = execution.errorCode === "MAX_ITERATIONS"
        ? "已达到分析轮数上限，可继续完成剩余计划。" : `运行未完成（${execution.errorCode || execution.status}），已恢复保存的进度。`;
    }
  }
  captureResponse(response);
}
async function recoverExecution(response) {
  const id = response.diagnostics?.runId;
  if (!id) return;
  try {
    const result = await request(`/api/runs/${encodeURIComponent(id)}/execution`, { headers: { "X-Qiqi-User": response.username } });
    if (!result.ok) return; // Older runs legitimately have no journal.
    const snapshot = await result.json();
    applyExecution(response, snapshot, true);
    if (response.diagnostics && snapshot.status !== "RUNNING")
      showDiagnostics(response, { ...response.diagnostics, status: snapshot.status, errorCode: snapshot.errorCode });
    if (snapshot.status === "RUNNING" && response.card.isConnected && response.username === user.value) {
      clearTimeout(response.recoveryTimer);
      response.recoveryTimer = setTimeout(() => {
        if (response.card.isConnected && response.username === user.value) recoverExecution(response);
      }, 1500);
    }
    if (response.savedTurn) response.savedTurn.recoverExecution = false;
    captureResponse(response, true);
  } catch { /* Keep the last local snapshot available while offline. */ }
}
function handleEvent(event, response) {
  const data = event.data || {};
  switch (event.type) {
    case "EXECUTION_UPDATE":
      applyExecution(response, data.execution);
      break;
    case "EXCEED_MAX_ITERS":
      response.failed = true;
      response.error.hidden = false;
      response.error.textContent = `已达到分析轮数上限（${data.maxIters || "配置值"}），已保留计划与证据，可继续完成剩余任务。`;
      break;
    case "RUN_START":
      showDiagnostics(response, data.run);
      break;
    case "RUN_END":
      response.runEnded = true;
      showDiagnostics(response, data.run);
      response.done = true;
      if (["FAILED", "INCOMPLETE", "CANCELLED"].includes(data.run?.status)) {
        response.failed = true;
        response.error.hidden = false;
        if (!response.error.textContent) response.error.textContent = data.run.errorCode === "PLAN_INCOMPLETE"
          ? "分析计划仍有未完成项目，已保留进度，可继续完成。"
          : `本次运行未完成（${data.run.errorCode || data.run.status}），请查看运行详情。`;
      }
      break;
    case "TEXT_BLOCK_DELTA":
      if (
        response.replyId &&
        data.replyId !== response.replyId &&
        response.text
      )
        response.text += "\n\n";
      response.replyId = data.replyId;
      response.text += data.delta || "";
      response.progress.textContent = "正在生成分析…";
      scheduleRender(response);
      break;
    case "TOOL_CALL_START": {
      response.trace.hidden = false;
      if (!response.tools.has(data.toolCallId)) {
        const step = element(
          "li",
          "",
          toolNames[data.toolCallName] || "处理数据",
        );
        response.tools.set(data.toolCallId, step);
        response.steps.append(step);
      }
      const label = toolNames[data.toolCallName] || "处理数据";
      response.summary.textContent = `分析中 · ${label}`;
      response.progress.textContent = label + "…";
      if (response.card.isConnected) {
        refreshControls();
        follow();
      }
      break;
    }
    case "TOOL_RESULT_TEXT_DELTA":
      if (data.toolCallName === "generate_chart")
        response.chartToolText.set(data.toolCallId,
          (response.chartToolText.get(data.toolCallId) || "") + (data.delta || ""));
      if (["execute_sql", "analyze_file"].includes(data.toolCallName))
        response.results.set(
          data.toolCallId,
          (response.results.get(data.toolCallId) || "") + (data.delta || ""),
        );
      break;
    case "TOOL_RESULT_END": {
      if (data.toolCallName === "web_search" && data.state === "success")
        for (const source of data.metadata?.webSearch?.sources || []) addWebSource(response, source);
      if (data.toolCallName === "generate_chart") {
        if (data.state === "error") addChartError(response,
          response.chartToolText.get(data.toolCallId) || "图表生成失败，已有查询结果仍可使用。");
        else if (data.metadata?.chart) addChart(response, data.metadata.chart);
      }
      const step = response.tools.get(data.toolCallId);
      if (step) {
        const failed = data.state === "error";
        step.textContent += failed ? " · 未成功" : " · 完成";
        step.classList.toggle("failed", failed);
      }
      if (response.results.has(data.toolCallId))
        addEvidence(response, response.results.get(data.toolCallId));
      break;
    }
    case "ERROR":
      response.failed = true;
      response.error.hidden = false;
      response.error.textContent = data.message || "分析失败，请稍后重试。";
      break;
    case "AGENT_END":
      response.done = true;
      break;
  }
}
async function consumeStream(body, response) {
  if (!body) throw new Error("未收到响应内容，请重试。");
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const dispatch = (block) => {
    const payload = block
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trimStart())
      .join("\n");
    if (payload && payload !== "[DONE]")
      handleEvent(JSON.parse(payload), response);
  };
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += done
        ? decoder.decode()
        : decoder.decode(value, { stream: true });
      let match;
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        dispatch(buffer.slice(0, match.index));
        buffer = buffer.slice(match.index + match[0].length);
      }
      if (done) {
        if (buffer.trim()) dispatch(buffer);
        break;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
async function send(query) {
  if (currentRun() || !state.configured || state.historyLoading || !query.trim()) return;
  if (state.attachment?.conversationId === state.conversationId) {
    query += `\n\n已上传 CSV：${state.attachment.name}；fileId=${state.attachment.fileId}。请先用 analyze_file 预览，统计使用完整文件。`;
    state.attachment = null;
    $("#file-status").textContent = "";
  }
  state.pinned = true;
  const controller = new AbortController();
  let chat = activeChat();
  if (!chat) {
    chat = {
      id: crypto.randomUUID(),
      runtimeId: state.conversationId,
      title: query.replace(/\s+/g, " ").slice(0, 48),
      updatedAt: Date.now(),
      turns: [],
    };
    state.chats.push(chat);
    state.activeId = chat.id;
  }
  chat.updatedAt = Date.now();
  const turn = {
    query,
    time: new Date().toISOString(),
    text: "",
    pending: true,
    status: "正在分析…",
    steps: [],
    evidence: [],
  };
  chat.turns.push(turn);
  saveHistory();
  renderHistory();
  refreshControls();
  $("#welcome").hidden = true;
  messages.append(element("article", "message user", query));
  const response = createResponse(query, turn.time, chat.runtimeId);
  response.savedTurn = turn;
  state.runs.set(chat.id, { controller, response });
  chat.draft = "";
  renderHistory();
  refreshControls();
  input.value = "";
  input.style.height = "";
  $("#status").textContent = "正在分析…";
  follow();
  let stopped = false;
  const online = state.webSearchEnabled && $("#online-search").checked;
  $("#online-search").checked = false;
  try {
    const result = await request("/api/chat/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Qiqi-User": user.value,
      },
      body: JSON.stringify({ query, conversationId: chat.runtimeId, online }),
      signal: controller.signal,
    });
    if (!result.ok) {
      let detail;
      try {
        detail = (await result.json()).error;
      } catch {
        /* fall back to status */
      }
      throw new Error(detail || `请求失败（${result.status}），请稍后重试。`);
    }
    await consumeStream(result.body, response);
    if ((!response.done || (response.diagnostics && !response.runEnded)) && !response.failed)
      throw new Error("连接已中断，回答可能不完整。请重新提问。");
  } catch (error) {
    if (error.name === "AbortError") stopped = true;
    else {
      response.failed = true;
      response.error.hidden = false;
      response.error.textContent = error.message || "连接失败，请稍后重试。";
    }
  } finally {
    if (!stopped && !response.failed)
      await finishTyping(response, controller.signal);
    stopped ||= controller.signal.aborted;
    render(response);
    response.progress.classList.remove("busy");
    response.progress.textContent = stopped
      ? "已停止 · 已保留生成内容"
      : response.failed
        ? "本次分析未完成"
        : "分析完成";
    response.summary.textContent = `分析过程 · ${response.tools.size} 次工具调用${stopped || response.failed ? " · 已中断" : ""}`;
    response.actions.hidden = !response.text;
    state.runs.delete(chat.id);
    if (response.failed || stopped) {
      // A cancelled server turn may still be unwinding. Use a fresh state slot.
      if (!state.workspaceEnabled) chat.runtimeId = crypto.randomUUID();
      if (state.activeId === chat.id) state.conversationId = chat.runtimeId;
      response.progress.textContent += state.workspaceEnabled ? " · 可从已保存上下文继续" : " · 下次提问将开启新会话";
      const retry = element("button", "", state.workspaceEnabled ? "继续完成" : "重新提问");
      retry.addEventListener("click", () => {
        if (currentRun()) return;
        input.value = state.workspaceEnabled ? `继续完成这个问题：${query}。先核对已完成的证据，再处理剩余部分。` : query;
        resizeInput();
        refreshControls();
        input.focus();
      });
      response.actions.append(retry);
      response.actions.hidden = false;
    }
    turn.pending = false;
    if (response.execution?.status === "RUNNING") {
      applyExecution(response, { ...response.execution, revision: response.execution.revision + 1,
        status: stopped ? "CANCELLED" : "INCOMPLETE",
        progress: stopped ? "分析已停止，已保留进度" : "连接已中断，已保留进度",
        tools: response.execution.tools.map(tool => ["QUEUED", "RUNNING"].includes(tool.status)
          ? { ...tool, status: stopped ? "CANCELLED" : "INCOMPLETE", result: "连接中断，完整状态可从服务端恢复" } : tool) });
      // A client-side interruption is provisional; server revision stays authoritative on refresh.
      response.execution.revision--;
    }
    captureResponse(response, true);
    if (state.activeId !== chat.id) chat.unread = true;
    saveHistory();
    renderHistory();
    refreshControls();
    if (response.card.isConnected) follow();
  }
}
function resizeInput() {
  input.style.height = "auto";
  input.style.height = Math.min(input.scrollHeight, 150) + "px";
}
input.addEventListener("input", () => {
  resizeInput();
  refreshControls();
});
input.addEventListener("keydown", (event) => {
  if (
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing &&
    window.matchMedia("(min-width: 761px)").matches
  ) {
    event.preventDefault();
    if (!currentRun()) $("#composer").requestSubmit();
  }
});
$("#composer").addEventListener("submit", (event) => {
  event.preventDefault();
  send(input.value.trim());
});
$("#stop").addEventListener("click", () => stopRun(currentRun()));
$("#new-chat").addEventListener("click", () => {
  if (!$("#settings-page").hidden) closeSettings();
  resetConversation();
});
user.addEventListener("change", async () => {
  updateScope();
  if (state.workspaceEnabled) await loadRemoteHistory();
  loadHistory();
  toast("已切换身份和对话记录");
});
document.querySelectorAll(".suggestion").forEach((button) =>
  button.addEventListener("click", () => {
    input.value = button.dataset.prompt;
    resizeInput();
    refreshControls();
    input.focus();
  }),
);
$("#file-upload").addEventListener("change", async event => {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  if (!state.workspaceEnabled || !/\.csv$/i.test(file.name) || file.size > 2 * 1024 * 1024) {
    toast("请上传不超过 2 MB 的 UTF-8 CSV 文件"); return;
  }
  const conversationId = state.conversationId, username = user.value;
  $("#file-status").textContent = "正在读取文件…";
  try {
    const result = await request("/api/files", { method: "POST", headers: { "Content-Type": "application/json", "X-Qiqi-User": username },
      body: JSON.stringify({ name: file.name, content: await file.text(), conversationId }) });
    if (!result.ok) { const error = await result.json(); throw new Error(error.error || "文件上传失败"); }
    const data = await result.json();
    if (state.conversationId !== conversationId || user.value !== username) return;
    state.attachment = { ...data, conversationId };
    $("#file-status").textContent = `${data.name} · ${data.totalRows} 行 · ${data.columns.join("、")}`;
    input.value ||= "请分析这个文件，先介绍数据，再按合适的维度汇总。";
    resizeInput(); refreshControls();
  } catch (error) { $("#file-status").textContent = error.message || "文件上传失败"; }
});
loadMeta().catch(() => {
  $("#notice").textContent = "无法连接服务，请确认服务已启动后刷新页面。";
  $("#notice").hidden = false;
  $("#status").textContent = "连接失败";
  $("#connection-label").textContent = "服务未连接";
});
