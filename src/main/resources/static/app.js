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
const modeNames = { AUTO: "自动", FAST: "快速执行", REVIEW: "先确认方案" };
function syncModePicker() {
  const value = $("#analysis-mode").value;
  $("#analysis-mode-label").textContent = modeNames[value] || modeNames.AUTO;
  $("#analysis-mode-trigger").setAttribute("aria-label", `执行方式：${modeNames[value] || modeNames.AUTO}`);
  document.querySelectorAll("#analysis-mode-menu [data-mode]").forEach(button =>
    button.setAttribute("aria-checked", String(button.dataset.mode === value)));
}
function closeComposerMenus() {
  $("#composer-options-menu").hidden = true;
  $("#analysis-mode-menu").hidden = true;
  $("#more-input-options").setAttribute("aria-expanded", "false");
  $("#analysis-mode-trigger").setAttribute("aria-expanded", "false");
}
function toggleComposerMenu(menuId, triggerId) {
  const wasOpen = !$(menuId).hidden;
  closeComposerMenus();
  if (!wasOpen) {
    $(menuId).hidden = false;
    $(triggerId).setAttribute("aria-expanded", "true");
  }
}
$("#more-input-options").addEventListener("click", () => toggleComposerMenu("#composer-options-menu", "#more-input-options"));
$("#analysis-mode-trigger").addEventListener("click", () => toggleComposerMenu("#analysis-mode-menu", "#analysis-mode-trigger"));
document.querySelectorAll("#analysis-mode-menu [data-mode]").forEach(button => button.addEventListener("click", () => {
  $("#analysis-mode").value = button.dataset.mode;
  $("#analysis-mode").dispatchEvent(new Event("change"));
  closeComposerMenus();
  $("#analysis-mode-trigger").focus();
}));
$("#analysis-mode").addEventListener("change", syncModePicker);
$("#online-search").addEventListener("change", closeComposerMenus);
document.addEventListener("click", event => {
  if (!event.target.closest(".composer-options, .analysis-mode-picker")) closeComposerMenus();
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && (!$("#composer-options-menu").hidden || !$("#analysis-mode-menu").hidden)) {
    const trigger = $("#analysis-mode-menu").hidden ? $("#more-input-options") : $("#analysis-mode-trigger");
    closeComposerMenus(); trigger.focus(); event.preventDefault();
  }
});
syncModePicker();
function showWorkspacePage(page) {
  const panel = page === "data" ? $("#data-center-page") : null;
  $("#data-center-page").hidden = panel !== $("#data-center-page");
  $("#welcome").hidden = !!panel || !!activeChat();
  messages.hidden = !!panel;
  const dock = $(".composer-dock");
  dock.hidden = !!panel;
  if (!panel && !activeChat()) $("#welcome").insertBefore(dock, $(".suggestion-heading"));
  else $(".workspace").append(dock);
  $("#open-data-center").classList.toggle("active", page === "data");
  $("#conversation-title").textContent = page === "data" ? "数据中心" : activeChat()?.title || "新建分析";
  scrollArea.scrollTop = 0;
}
const catalog = { tab: "tables", tables: [], files: [], selected: null, loaded: false };
function catalogItems() { return catalog.tab === "tables" ? catalog.tables : catalog.files; }
function catalogEmpty() {
  const detail = $("#data-center-detail");
  detail.replaceChildren();
  const empty = element("div", "catalog-empty");
  empty.append(element("div", "catalog-empty-icon", catalog.tab === "files" ? "▤" : "▦"),
    element("h2", "", catalog.tab === "files" ? "当前会话还没有文件" : "选择一个数据表"),
    element("p", "", catalog.tab === "files" ? "上传 CSV、XLSX 或 XLS 文件，即可在当前会话中分析。" : "从左侧查看已授权的数据表及字段。"));
  if (catalog.tab === "files") {
    const add = element("button", "catalog-primary", "添加数据");
    add.type = "button"; add.addEventListener("click", () => $("#data-center-upload").click()); empty.append(add);
  }
  detail.append(empty);
}
function catalogDetail() {
  const item = catalogItems().find(entry => (entry.fileId || entry.name) === catalog.selected);
  if (!item) { catalogEmpty(); return; }
  const detail = $("#data-center-detail");
  detail.replaceChildren();
  const heading = element("div", "catalog-detail-heading");
  const title = element("div", "");
  title.append(element("span", "source-kind", catalog.tab === "files" ? "当前会话文件" : "已授权数据表"), element("h2", "", item.name));
  const analyze = element("button", "catalog-primary", "＋ 去分析"); analyze.type = "button";
  analyze.addEventListener("click", () => {
    showWorkspacePage(null);
    if (catalog.tab === "files") {
      state.attachment = { ...item, conversationId: state.conversationId };
      $("#file-status").textContent = `${item.name} · ${item.totalRows} 行`;
      input.value ||= "请分析这个文件，先介绍数据，再按合适的维度汇总。";
    } else {
      state.attachment = null; $("#file-status").textContent = "";
      input.value ||= `请介绍 ${item.name} 数据表，并根据表结构给出可分析的方向。`;
    }
    resizeInput(); refreshControls(); input.focus();
  });
  heading.append(title, analyze); detail.append(heading);
  detail.append(element("p", "catalog-description", catalog.tab === "files"
    ? `该文件有 ${item.totalRows} 行、${item.columns.length} 列，仅能在当前会话中分析。`
    : item.description || "当前账号可查询此表。"));
  const meta = element("div", "catalog-meta");
  meta.append(element("span", "", catalog.tab === "files" ? "文件格式" : "数据库"), element("strong", "", catalog.tab === "files" ? item.name.split(".").pop().toUpperCase() : item.databaseProduct || "当前数据库"),
    element("span", "", catalog.tab === "files" ? "数据行数" : "字段数"), element("strong", "", String(catalog.tab === "files" ? item.totalRows : (item.columns || []).length)));
  detail.append(meta, element("h3", "catalog-section-title", catalog.tab === "files" ? "文件字段" : "表字段"));
  const fields = element("div", "catalog-fields");
  for (const column of item.columns || []) {
    const row = element("div", "catalog-field");
    row.append(element("strong", "", typeof column === "string" ? column : column.name),
      element("span", "", typeof column === "string" ? "文件列" : `${column.type || ""}${column.nullable === false ? " · 必填" : ""}`)); fields.append(row);
  }
  detail.append(fields);
  if (catalog.tab === "tables" && item.foreignKeys?.length) {
    detail.append(element("h3", "catalog-section-title", "关联关系"));
    for (const key of item.foreignKeys) detail.append(element("p", "catalog-relation", `${key.column} → ${key.references}`));
  }
}
function renderCatalog() {
  $("#data-tab-tables").classList.toggle("active", catalog.tab === "tables");
  $("#data-tab-files").classList.toggle("active", catalog.tab === "files");
  $("#data-tab-tables").setAttribute("aria-selected", String(catalog.tab === "tables"));
  $("#data-tab-files").setAttribute("aria-selected", String(catalog.tab === "files"));
  $("#data-center-scope").textContent = catalog.tab === "files" ? "仅显示当前会话上传的文件" : "当前账号可查询的数据表";
  const list = $("#data-center-content"); list.replaceChildren();
  const query = $("#data-center-search").value.trim().toLocaleLowerCase();
  const items = catalogItems().filter(item => `${item.name} ${item.description || ""}`.toLocaleLowerCase().includes(query));
  for (const item of items) {
    const button = element("button", "catalog-item"); button.type = "button";
    button.classList.toggle("active", catalog.selected === (item.fileId || item.name));
    button.append(element("span", "catalog-item-icon", catalog.tab === "files" ? "▤" : "▦"), element("span", "catalog-item-text", item.name));
    button.addEventListener("click", () => { catalog.selected = item.fileId || item.name; renderCatalog(); });
    list.append(button);
  }
  if (!items.length) list.append(element("p", "catalog-list-empty", query ? "没有匹配结果" : catalog.tab === "files" ? "当前会话暂无文件" : "当前账号暂无数据表"));
  catalogDetail();
}
async function loadCatalog() {
  const list = $("#data-center-content"); list.textContent = "正在读取数据目录…";
  const tab = catalog.tab, conversationId = state.conversationId, username = user.value;
  try {
    const response = await request(tab === "tables" ? "/api/data-center" : `/api/files?conversationId=${encodeURIComponent(conversationId)}`,
      { headers: { "X-Qiqi-User": username } });
    if (!response.ok) throw new Error("数据目录暂不可用");
    const data = await response.json();
    if (catalog.tab !== tab || state.conversationId !== conversationId || user.value !== username) return;
    if (tab === "tables") catalog.tables = data.tables || [];
    else catalog.files = Array.isArray(data) ? data : [];
    catalog.loaded = true;
    if (!catalogItems().some(item => (item.fileId || item.name) === catalog.selected)) catalog.selected = catalogItems()[0]?.fileId || catalogItems()[0]?.name || null;
    renderCatalog();
  } catch (error) {
    if (catalog.tab !== tab || state.conversationId !== conversationId || user.value !== username) return;
    list.textContent = error.message || "数据目录暂不可用"; catalogEmpty();
  }
}
$("#open-data-center").addEventListener("click", () => { showWorkspacePage("data"); loadCatalog(); });
$("#data-center-refresh").addEventListener("click", loadCatalog);
$("#data-center-search").addEventListener("input", renderCatalog);
for (const [id, tab] of [["#data-tab-tables", "tables"], ["#data-tab-files", "files"]])
  $(id).addEventListener("click", () => { catalog.tab = tab; catalog.selected = null; loadCatalog(); });
$("#data-center-add").addEventListener("click", () => {
  catalog.tab = "files"; catalog.selected = null; renderCatalog(); $("#data-center-upload").click();
});
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
  submit_analysis_plan: "提交分析计划",
  record_analysis_plan: "记录分析计划",
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
  creative: ["创意功能", "管理需明确调用的创意 skill。"],
  memory: ["记忆管理", "查看长期记忆功能的接入计划。"],
};
function settingsMessage(text) {
  $("#model-load-status").textContent = text;
  $("#database-load-status").textContent = text;
  $("#creative-load-status").textContent = text;
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
  $("#hyperframes-enabled").checked = !!data.creative?.hyperframesEnabled;
  $("#hyperframes-enabled").disabled = false;
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
    if (page === "model" || page === "database" || page === "creative") loadServerSettings();
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
    if (kind === "model") {
      $("#model-key").value = "";
      $("#model-key-state").textContent = data.model.keyConfigured
        ? "已配置 · 不回显"
        : "未配置";
    }
    settingsMessage(kind === "creative" ? "已保存，下一轮消息立即生效。" : "已保存。重启本地服务后生效，当前运行中的分析不受影响。");
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
$("#creative-settings-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveServerSettings("creative", { hyperframesEnabled: $("#hyperframes-enabled").checked });
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
let lastScrollTop = scrollArea.scrollTop;
let lastTouchY = null;
function detachFromLatest() {
  state.pinned = false;
  $("#jump-latest").hidden = !messages.childElementCount;
}
scrollArea.addEventListener("wheel", event => {
  if (event.deltaY < 0) detachFromLatest();
}, { passive: true });
scrollArea.addEventListener("touchstart", event => {
  lastTouchY = event.touches[0]?.clientY ?? null;
}, { passive: true });
scrollArea.addEventListener("touchmove", event => {
  const y = event.touches[0]?.clientY;
  if (y != null && lastTouchY != null && y > lastTouchY + 2) detachFromLatest();
  if (y != null) lastTouchY = y;
}, { passive: true });
scrollArea.addEventListener(
  "scroll",
  () => {
    const distance = scrollArea.scrollHeight - scrollArea.scrollTop - scrollArea.clientHeight;
    if (distance > 8) state.pinned = false;
    else if (scrollArea.scrollTop > lastScrollTop + 1) state.pinned = true;
    lastScrollTop = scrollArea.scrollTop;
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
  let lastGroup = "";
  for (const chat of [...state.chats].sort(
    (a, b) => b.updatedAt - a.updatedAt,
  )) {
    const age = Date.now() - Number(chat.updatedAt);
    const group = age < 86400000 ? "今天" : age < 7 * 86400000 ? "近 7 天" : "更早";
    if (group !== lastGroup) { history.append(element("div", "history-group", group)); lastGroup = group; }
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
    button.addEventListener("click", () => {
      openConversation(chat.id);
      setHistoryExpanded(false);
    });
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
  showWorkspacePage(null);
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
    response.analysisMode = turn.analysisMode || "AUTO";
    response.text = turn.text || "";
    response.restoredComplete = Boolean(response.text) && !turn.pending && Boolean(
      turn.finalReady ||
      turn.execution?.finalNarrationId ||
      !/未完成|中断|停止/.test(turn.status || ""),
    );
    render(response);
    response.progress.classList.remove("busy");
    response.progress.textContent = turn.status || "分析完成";
    response.error.textContent = turn.error || "";
    response.error.hidden = !turn.error;
    if (turn.error && /未完成项目|PLAN_INCOMPLETE/.test(turn.error))
      response.error.className = "response-note";
    response.savedTurn = turn;
    for (const raw of turn.evidence || []) addEvidence(response, raw);
    for (const chart of turn.charts || []) addChart(response, chart);
    for (const message of turn.chartErrors || []) addChartError(response, message);
    for (const source of turn.webSources || []) addWebSource(response, source);
    if (turn.diagnostics) showDiagnostics(response, turn.diagnostics);
    if (turn.execution) applyExecution(response, turn.execution, true);
    else if (turn.steps?.length) {
      for (const step of turn.steps) {
        const row = timelineRow(step.failed ? "failed" : "tool");
        row.body.append(element("div", "timeline-tool-name", step.text));
        if (step.failed) row.item.classList.add("failed");
        response.executionList.append(row.item);
      }
      response.executionDetails.hidden = false;
    }
    if (state.executionEnabled && turn.diagnostics?.runId && (turn.recoverExecution || turn.diagnostics.status === "RUNNING" || (turn.charts || []).some(chart => !chart.option)))
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
  showWorkspacePage(null);
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
    steps: [...response.toolDetails.values()].map((view) => ({
      text: view.title?.textContent || view.item.textContent,
      failed: view.item.classList.contains("failed"),
    })),
    evidence: [...response.results.values()],
    charts: [...response.charts.values()],
    chartErrors: [...response.chartErrors],
    webSources: [...response.webSources.values()],
    diagnostics: response.diagnostics,
    execution: response.execution ? { ...response.execution, text: "" } : null,
    finalReady: response.finalReady,
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
    : "联网搜索暂未启用，需配置搜索服务。数据库和文件分析不受影响。";
  updateScope();
  state.workspaceEnabled = Boolean(meta.workspaceEnabled);
  state.executionEnabled = Boolean(meta.executionEnabled);
  await loadRemoteHistory();
  loadHistory();
  refreshControls();
}
function timelineRow(kind, extraClass = "") {
  const item = element("div", extraClass ? `execution-item ${extraClass}` : "execution-item");
  const dot = element("span", `execution-dot ${kind}`);
  const body = element("div", "execution-body");
  item.append(dot, body);
  return { item, dot, body };
}
function setNotice(response, message, tone = "error") {
  response.error.hidden = !message;
  response.error.className = tone === "note" ? "response-note" : "response-error";
  response.error.textContent = message || "";
  response.error.setAttribute("role", tone === "note" ? "status" : "alert");
  if (tone === "error" && message) response.failed = true;
}
function syncPresentation(response) {
  const execution = response.execution;
  const finalReplyReady = Boolean(
    response.agentEnded ||
    response.restoredComplete ||
    (execution?.finalNarrationId && execution.narrations?.some(
      (item) => item.id === execution.finalNarrationId && item.content,
    )),
  );
  const showFinal = finalReplyReady && Boolean(response.text);
  response.finalReady = showFinal;
  response.finalResult.hidden = !showFinal;
  response.analysisNarrative.hidden = showFinal || Boolean(execution);
  response.card.classList.toggle("is-complete", showFinal);
  if (showFinal) response.actions.hidden = false;
  else if (!response.failed) response.actions.hidden = true;
  response.executionDetails.hidden =
    !response.executionList.childNodes.length &&
    !response.executionWarnings.childNodes.length;
  if (showFinal) revealCharts(response);
  if (showFinal) void checkVideoOffer(response);
}
async function checkVideoOffer(response) {
  const runId = response.diagnostics?.runId;
  if (demoMode || !runId || response.videoOfferChecked ||
      !["SUCCEEDED", "PARTIAL"].includes(response.diagnostics.status)) return;
  response.videoOfferChecked = true;
  try {
    const result = await request(`/api/runs/${encodeURIComponent(runId)}/video-offer`,
      { headers: { "X-Qiqi-User": response.username } });
    if (!result.ok) return;
    const offer = await result.json();
    if (!offer.available || !response.card.isConnected) return;
    showVideoOffer(response, offer);
  } catch { /* Video suggestions never interrupt the report. */ }
}
function showVideoOffer(response, offer) {
  const host = response.videoHost;
  host.hidden = false;
  host.replaceChildren();
  const title = element("strong", "", "把这份分析做成短视频？");
  const note = element("span", "", "使用已核实的查询结果，生成 15 秒无配音数据视频。");
  const accept = element("button", "", offer.videoId ? "查看短视频" : "用 HyperFrames 生成");
  const dismiss = element("button", "", "暂不");
  accept.type = dismiss.type = "button";
  dismiss.addEventListener("click", () => { host.hidden = true; });
  accept.addEventListener("click", async () => {
    accept.disabled = true;
    accept.textContent = offer.videoId ? "正在打开…" : "正在生成，约需几十秒…";
    try {
      let videoId = offer.videoId;
      if (!videoId) {
        const result = await request(`/api/runs/${encodeURIComponent(response.diagnostics.runId)}/video`, {
          method: "POST", headers: { "X-Qiqi-User": response.username },
        });
        if (!result.ok) {
          let detail = "视频生成失败，请检查本地 HyperFrames 和 FFmpeg。";
          try { detail = (await result.json()).error || detail; } catch { /* Keep fallback. */ }
          throw new Error(detail);
        }
        videoId = (await result.json()).id;
      }
      const url = `/api/runs/${encodeURIComponent(response.diagnostics.runId)}/video/${encodeURIComponent(videoId)}`;
      const result = await request(url, { headers: { "X-Qiqi-User": response.username } });
      if (!result.ok) throw new Error("视频文件不可用，请稍后重试。");
      if (response.videoObjectUrl) URL.revokeObjectURL(response.videoObjectUrl);
      response.videoObjectUrl = URL.createObjectURL(await result.blob());
      host.replaceChildren(element("strong", "", "分析短视频已生成"));
      const player = element("video", "report-video-player");
      player.controls = true; player.preload = "metadata"; player.src = response.videoObjectUrl;
      const download = element("a", "report-video-download", "下载 MP4");
      download.href = response.videoObjectUrl; download.download = "Qiqi-分析短视频.mp4";
      host.append(player, download, element("small", "", `查询证据：${offer.queryId}`));
    } catch (error) {
      accept.disabled = false;
      accept.textContent = "重试生成短视频";
      note.textContent = error.message || "视频生成失败";
    }
  });
  host.append(title, note, accept, dismiss);
}
function reconcileChildren(target, next) {
  const current = [...target.childNodes];
  for (let index = 0; index < next.length; index++) {
    if (!current[index]) target.append(next[index]);
    else if (!current[index].isEqualNode(next[index])) current[index].replaceWith(next[index]);
  }
  for (let index = next.length; index < current.length; index++) current[index].remove();
}
function paintMarkdown(target, markdown) {
  const staging = element("div");
  renderMarkdown(staging, markdown);
  reconcileChildren(target, [...staging.childNodes]);
}
function renderFinalReport(response, text) {
  const staging = element("div");
  renderMarkdown(staging, text);
  const children = [...staging.childNodes];
  if (!children.length) {
    reconcileChildren(response.resultConclusionBody, []);
    reconcileChildren(response.reportBody, []);
    return;
  }
  let split = 1;
  if (/^H[12]$/.test(children[0].nodeName)) {
    while (split < children.length && !/^H[12]$/.test(children[split].nodeName)) split++;
  }
  reconcileChildren(response.resultConclusionBody, children.slice(0, split));
  reconcileChildren(response.reportBody, children.slice(split));
  response.reportBody.hidden = split >= children.length;
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
  const analysisProgress = element("section", "analysis-progress");
  const progressHeader = element("div", "analysis-progress-header");
  const progress = element("div", "response-status busy", "正在理解你的问题…");
  const progressCount = element("span", "analysis-count", "准备中");
  progressHeader.append(progress, progressCount);
  const todoArea = element("details", "todo-panel");
  todoArea.setAttribute("aria-label", "分析计划");
  todoArea.hidden = true;
  analysisProgress.append(progressHeader, todoArea);
  const analysisNarrative = element("section", "analysis-narrative");
  const narrativeLabel = element("div", "analysis-label", "当前分析思路");
  const narrativeBody = element("div", "analysis-narrative-body markdown-body",
    "正在结合计划核对数据与业务口径。");
  const stageFindings = element("div", "stage-findings");
  analysisNarrative.append(narrativeLabel, narrativeBody, stageFindings);
  const finalResult = element("section", "final-result report");
  finalResult.hidden = true;
  const resultConclusion = element("section", "result-conclusion");
  resultConclusion.append(element("div", "result-section-label", "主要结论"));
  const resultConclusionBody = element("div", "result-conclusion-body markdown-body");
  resultConclusion.append(resultConclusionBody);
  const chartArea = element("div", "chart-results");
  const dataViews = element("section", "data-views");
  dataViews.hidden = true;
  const reportBody = element("section", "report-body markdown-body");
  const sourceArea = element("section", "web-sources");
  sourceArea.append(element("strong", "", "网络来源"));
  sourceArea.hidden = true;
  const evidence = element("div", "query-evidence");
  finalResult.append(resultConclusion, chartArea, dataViews, reportBody, sourceArea, evidence);
  const executionDetails = element("details", "execution-details");
  const executionSummary = element("summary", "execution-summary", "分析过程 · 准备中");
  const executionWarnings = element("div", "execution-warnings");
  const executionList = element("div", "execution-list");
  executionDetails.append(executionSummary, executionWarnings, executionList);
  executionSummary.addEventListener("click", () => { response.processTouched = true; });
  const error = element("div", "response-error");
  error.hidden = true;
  error.setAttribute("role", "alert");
  const diagnosticsArea = element("details", "run-diagnostics");
  diagnosticsArea.hidden = true;
  const diagnosticsSummary = element("summary", "", "运行详情");
  const diagnosticsBody = element("div", "diagnostics-body");
  diagnosticsArea.append(diagnosticsSummary, diagnosticsBody);
  const actions = element("div", "report-actions");
  actions.hidden = true;
  const videoHost = element("section", "report-video-offer");
  videoHost.hidden = true;
  const summary = element("span");
  const steps = { children: [] };
  const planHost = element("section", "plan-card-host");
  planHost.hidden = true;
  const response = {
    card,
    report: finalResult,
    reportBody,
    resultConclusion,
    resultConclusionBody,
    finalResult,
    analysisProgress,
    progressCount,
    analysisNarrative,
    narrativeBody,
    stageFindings,
    progress,
    error,
    summary,
    steps,
    todoArea,
    planHost,
    executionDetails,
    executionSummary,
    executionWarnings,
    executionList,
    narrationDetails: new Map(),
    processInitialized: false,
    processTouched: false,
    execution: null,
    toolDetails: new Map(),
    actions,
    videoHost,
    videoOfferChecked: false,
    evidence,
    chartArea,
    dataViews,
    sourceArea,
    diagnosticsArea,
    diagnosticsSummary,
    diagnosticsBody,
    diagnostics: null,
    username: user.value,
    conversationId,
    webSources: new Map(),
    charts: new Map(),
    renderedCharts: new Set(),
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
    agentEnded: false,
    restoredComplete: false,
    finalReady: false,
    runEnded: false,
    failed: false,
    queries: new Set(),
    visualizedQueries: new Set(),
    requestedEvidence: new Set(),
    dataViewRenderers: new Map(),
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
  const htmlButton = element("button", "", "预览报告");
  htmlButton.addEventListener("click", async () => {
    htmlButton.disabled = true;
    try {
      const html = await portableHtmlReport(response);
      const dialog = $("#report-preview-dialog");
      const frame = $("#report-preview-frame");
      frame.srcdoc = html;
      $("#download-html-report").onclick = () => downloadHtmlReport(html);
      $("#print-html-report").onclick = () => frame.contentWindow?.print();
      $("#close-report-preview").onclick = () => dialog.close();
      dialog.showModal();
    } catch { toast("网页报告生成失败，请稍后重试"); }
    finally { htmlButton.disabled = false; }
  });
  actions.append(copyButton, exportButton, htmlButton);
  finalResult.append(videoHost, actions);
  card.append(heading, planHost, analysisProgress, analysisNarrative, executionDetails,
    diagnosticsArea, error, finalResult);
  messages.append(card);
  return response;
}
function paintResponse(response, text) {
  if (text) {
    if (response.agentEnded || response.restoredComplete || response.finalReady)
      renderFinalReport(response, text);
    else if (!response.execution) paintMarkdown(response.narrativeBody, text);
  }
  syncPresentation(response);
  captureResponse(response);
  if (response.card.isConnected) follow();
}
function render(response) {
  clearTimeout(response.renderTimer);
  response.renderTimer = null;
  response.visibleLength = response.text.length;
  response.report.classList.remove("typing");
  response.narrativeBody.classList.remove("typing");
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
    response.report.classList.remove("typing");
    response.narrativeBody.classList.remove("typing");
    if (!response.execution && !response.agentEnded && !response.finalReady)
      response.narrativeBody.classList.toggle("typing", animate);
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
function numericValue(value) {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string" || !/^-?(?:\d+\.?\d*|\.\d+)$/.test(value.trim())) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}
function compactNumber(value) {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(value);
}
function renderDataView(response, result) {
  const columns = Array.isArray(result.columns) ? result.columns.filter(name => typeof name === "string").slice(0, 30) : [];
  const rows = Array.isArray(result.rows) ? result.rows.filter(row => row && typeof row === "object" && !Array.isArray(row)).slice(0, 200) : [];
  if (!columns.length || !rows.length) return false;
  const roles = NomanCharts.fields(columns, rows);
  const numeric = roles.filter(field => field.role === "measure").map(field => field.name);
  const inferred = NomanCharts.infer(columns, rows);
  let config = { ...inferred, ...(response.savedTurn?.visualConfigs?.[result.queryId] || {}) };
  const modes = ["概览", "表格", ...(numeric.length ? ["图表"] : [])];
  let metric = config.metric;
  const points = metric ? rows.map(row => ({ label: String(row[config.category] ?? "—"), value: numericValue(row[metric]) })).filter(point => point.value !== null) : [];
  const card = element("details", "data-view-card");
  card.dataset.queryId = result.queryId;
  card.open = !response.dataViews.children.length;
  card.append(element("summary", "data-view-title", `数据视图 · ${result.source?.name || `查询结果 ${response.dataViews.children.length + 1}`}`));
  const inner = element("div", "data-view-inner");
  const intro = element("div", "data-view-intro");
  intro.append(element("span", "", `${result.rowCount ?? rows.length} 行结果`),
    element("span", "", `${columns.length} 个字段`),
    element("span", "", `证据 ${result.queryId}`));
  if (result.truncated) intro.append(element("span", "data-view-warning", `仅展示前 ${rows.length} 行，图表基于预览数据`));
  const tabs = element("div", "data-view-tabs");
  tabs.setAttribute("role", "group"); tabs.setAttribute("aria-label", "数据展示形式");
  const stage = element("div", "data-view-stage");
  const chartHost = element("div", "data-analysis-chart");
  chartHost.hidden = true;
  const controls = element("div", "chart-explorer");
  const chartPicker = element("select");
  chartPicker.setAttribute("aria-label", "图表来源");
  const exploreChoice = element("option", "", "自主探索"); exploreChoice.value = "explore"; chartPicker.append(exploreChoice);
  const toolbar = element("div", "chart-explorer-fields");
  let selectedMode = "概览", localPlot, userTouched = false;
  const selects = {};
  function saveConfig() {
    if (response.savedTurn) {
      response.savedTurn.visualConfigs ||= {};
      response.savedTurn.visualConfigs[result.queryId] = { ...config };
      captureResponse(response, true);
    }
  }
  function selectField(key, label, options) {
    const wrap = element("label", "chart-field"); wrap.append(element("span", "", label));
    const select = element("select"); select.setAttribute("aria-label", label);
    for (const [value, text] of options) { const item = element("option", "", text); item.value = value; select.append(item); }
    select.value = config[key] ?? "";
    select.addEventListener("change", () => { userTouched = true; config[key] = select.value; metric = config.metric; saveConfig(); paint("图表"); });
    wrap.append(select); toolbar.append(wrap); selects[key] = select;
  }
  selectField("category", "分类", columns.map(name => [name, name]));
  selectField("metric", "指标", numeric.map(name => [name, name]));
  selectField("series", "系列", [["", "不分系列"], ...columns.map(name => [name, name])]);
  selectField("type", "图型", [["bar", "柱状对比"], ["horizontal_bar", "横向排名"], ["line", "趋势折线"], ["pie", "占比环形"]]);
  selectField("aggregate", "聚合", [["none", "原值"], ["sum", "求和"], ["mean", "平均"], ["count", "计数"]]);
  selectField("sort", "排序", [["original", "查询顺序"], ["asc", "数值升序"], ["desc", "数值降序"]]);
  const hint = element("p", "data-view-note", "探索当前查询快照，配置随分析保存；调整不改变原报告与分析图下载。重复分组需明确选择聚合，缺失值不补零。");
  controls.append(chartPicker, toolbar, hint);
  controls.hidden = true;
  chartPicker.addEventListener("change", () => { userTouched = true; paint("图表"); });
  function attachChart(figure, chart) {
    if (![...chartPicker.options].some(option => option.value === chart.id)) {
      const item = element("option", "", chart.title); item.value = chart.id; chartPicker.prepend(item);
    }
    figure.hidden = chartPicker.value !== chart.id;
    chartHost.append(figure);
    if (!userTouched && (selectedMode === "概览" || chartPicker.value === "explore")) {
      chartPicker.value = chart.id; card.open = true; paint("图表");
    }
  }
  function paint(mode, output = stage) {
    if (output === stage) {
      selectedMode = mode;
      if (localPlot) { localPlot.dispose(); localPlot = null; }
      controls.hidden = mode !== "图表";
      const analysis = mode === "图表" && chartPicker.value !== "explore";
      chartHost.hidden = !analysis;
      stage.hidden = analysis;
      toolbar.hidden = analysis;
      hint.hidden = analysis;
      for (const figure of chartHost.children) figure.hidden = figure.dataset.chartId !== chartPicker.value;
    }
    output.replaceChildren();
    if (output === stage) [...tabs.children].forEach(button => {
      button.classList.toggle("active", button.textContent === mode);
      button.setAttribute("aria-pressed", String(button.textContent === mode));
    });
    if (mode === "概览") {
      const grid = element("div", "data-kpis");
      const cards = [["结果行数", compactNumber(result.rowCount ?? rows.length)], ["可见字段", String(columns.length)]];
      if (rows.length === 1) for (const column of numeric.slice(0, 4))
        cards.push([column, compactNumber(numericValue(rows[0][column]))]);
      else if (points.length) {
        cards.push([`${metric} · 预览最小值`, compactNumber(Math.min(...points.map(point => point.value)))],
          [`${metric} · 预览最大值`, compactNumber(Math.max(...points.map(point => point.value)))]);
      }
      for (const [label, value] of cards) {
        const item = element("div", "data-kpi");
        item.append(element("span", "", label), element("strong", "", value)); grid.append(item);
      }
      output.append(grid);
    } else if (mode === "表格") {
      const wrap = element("div", "data-table-wrap");
      wrap.tabIndex = 0; wrap.setAttribute("role", "region"); wrap.setAttribute("aria-label", "查询数据表格");
      const table = element("table", "data-table");
      const head = element("thead"); const headRow = element("tr");
      columns.forEach(column => headRow.append(element("th", "", column))); head.append(headRow);
      const body = element("tbody");
      for (const row of rows) {
        const tr = element("tr"); columns.forEach(column => tr.append(element("td", "", row[column] == null ? "—" : String(row[column])))); body.append(tr);
      }
      table.append(head, body); wrap.append(table); output.append(wrap);
    } else if (mode === "图表" && output === stage && chartPicker.value === "explore") {
      const canvas = element("div", "chart-interactive");
      canvas.setAttribute("role", "img"); canvas.setAttribute("aria-label", "查询快照探索图表");
      output.append(canvas);
      try {
        localPlot = NomanCharts.mount(canvas, NomanCharts.compile(config, columns, rows));
        const download = element("button", "data-export-button", "下载探索图 PNG");
        download.type = "button";
        download.addEventListener("click", () => {
          const link = element("a"); link.href = localPlot.plot.getDataURL({ type: "png", pixelRatio: 2, backgroundColor: "#fff" });
          link.download = "Qiqi-查询快照探索.png"; link.click();
        });
        output.append(download);
      }
      catch (error) { canvas.remove(); output.append(element("p", "data-view-warning", error.message)); }
    }
  }
  for (const mode of modes) {
    const button = element("button", "", mode); button.type = "button";
    button.addEventListener("click", () => { userTouched = true; paint(mode); }); tabs.append(button);
  }
  const actions = element("div", "data-view-actions");
  if (state.workspaceEnabled && typeof result.queryId === "string") {
    const download = element("button", "data-export-button", result.truncated ? "↓ 下载预览 CSV" : "↓ 下载 CSV");
    download.type = "button";
    download.title = result.truncated ? "只包含当前预览行，不是完整查询结果" : "下载当前查询结果";
    download.addEventListener("click", () => downloadProtected(`/api/evidence/${encodeURIComponent(result.queryId)}/csv?conversationId=${encodeURIComponent(response.conversationId)}`, response.username, `Qiqi-查询结果-${response.dataViews.children.length + 1}.csv`));
    actions.append(download);
  }
  inner.append(intro, tabs, controls, chartHost, stage, actions); card.append(inner); response.dataViews.hidden = false;
  response.dataViewRenderers.set(result.queryId, { modes: ["概览", "表格"], paint, attachChart });
  response.dataViews.append(card); paint("概览");
  for (const figure of response.chartArea.querySelectorAll(".chart-card"))
    if (figure.dataset.queryId === result.queryId) {
      const chart = response.charts.get(figure.dataset.chartId);
      if (chart) attachChart(figure, chart);
    }
  return true;
}
function resolveDataView(response, result) {
  const id = result.queryId;
  if (response.visualizedQueries.has(id)) return;
  if (renderDataView(response, result)) {
    response.visualizedQueries.add(id);
    return;
  }
  if (!state.workspaceEnabled || response.requestedEvidence.has(id)) return;
  response.requestedEvidence.add(id);
  request(`/api/evidence/${encodeURIComponent(id)}?conversationId=${encodeURIComponent(response.conversationId)}`,
    { headers: { "X-Qiqi-User": response.username } }).then(async reply => {
    if (!reply.ok) return;
    const full = await reply.json();
    if (full.queryId === id && !response.visualizedQueries.has(id) && renderDataView(response, full))
      response.visualizedQueries.add(id);
  }).catch(() => { /* Old evidence can expire; the report and SQL remain available. */ });
}
function addEvidence(response, raw) {
  try {
    let result = JSON.parse(raw);
    if (typeof result === "string") result = JSON.parse(result);
    if (
      !result.queryId ||
      (!result.executedSql && !Array.isArray(result.columns)) ||
      typeof result.queryId !== "string"
    )
      return;
    resolveDataView(response, result);
    if (response.queries.has(result.queryId)) return;
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
      || typeof chart.title !== "string") return;
  const existing = response.charts.get(chart.id);
  if (existing) {
    if (!existing.option && chart.option) {
      existing.option = chart.option;
      response.renderedCharts.delete(chart.id);
      for (const card of response.card.querySelectorAll(".chart-card"))
        if (card.dataset.chartId === chart.id) card.remove();
      revealCharts(response);
    }
    return;
  }
  const safe = { id: chart.id, queryId: chart.queryId, title: chart.title, type: chart.type, source, option: chart.option };
  response.charts.set(safe.id, safe);
  revealCharts(response);
}
function renderChart(response, safe) {
  if (response.renderedCharts.has(safe.id)) return;
  response.renderedCharts.add(safe.id);
  const { source } = safe;
  const figure = element("figure", "chart-card");
  figure.dataset.chartId = safe.id;
  figure.dataset.queryId = safe.queryId;
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
  const renderer = response.dataViewRenderers.get(safe.queryId);
  if (renderer) renderer.attachChart(figure, safe);
  else response.chartArea.append(figure);
  if (safe.option && window.echarts) {
    const canvas = element("div", "chart-interactive");
    canvas.setAttribute("role", "img");
    canvas.setAttribute("aria-label", `${safe.title}，交互图表，查询证据 ${safe.queryId}`);
    image.before(canvas);
    try {
      NomanCharts.mount(canvas, safe.option);
      image.hidden = true;
      failure.hidden = true;
      image.addEventListener("error", () => { failure.hidden = true; });
    } catch { canvas.remove(); image.hidden = false; }
  }
}
function addChartError(response, message) {
  if (!message || response.chartErrors.has(message)) return;
  response.chartErrors.add(message);
  const warning = element("p", "execution-warning", `图表未生成：${message}`);
  warning.setAttribute("role", "status");
  response.executionWarnings.append(warning);
  response.executionDetails.open = true;
  syncPresentation(response);
}
function revealCharts(response) {
  const terminal = response.agentEnded || response.runEnded || response.restoredComplete ||
    (response.execution && response.execution.status !== "RUNNING");
  if (!terminal) return;
  for (const chart of response.charts.values()) renderChart(response, chart);
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
  syncPresentation(response);
}
function showDiagnostics(response, run) {
  if (!run || typeof run.runId !== "string" || !/^[a-f0-9-]{36}$/.test(run.runId)) return;
  response.diagnostics = run;
  response.diagnosticsArea.hidden = false;
  const labels = { RUNNING: "进行中", SUCCEEDED: "完成", PARTIAL: "已生成结果 · 有失败记录", FAILED: "失败", CANCELLED: "已取消", INCOMPLETE: "未完成" };
  const duration = Math.max(0, Number(run.durationMs) || 0);
  response.diagnosticsSummary.textContent = `运行详情 · ${labels[run.status] || "未知状态"} · ${(duration / 1000).toFixed(1)} 秒`;
  const id = element("p", "run-id", `运行编号：${run.runId}`);
  response.diagnosticsBody.replaceChildren(id);
  if (run.errorCode) response.diagnosticsBody.append(element("p", "", `故障代码：${run.errorCode}`));
  if (run.usage) response.diagnosticsBody.append(element("p", "", `Token：输入 ${run.usage.inputTokens} / 输出 ${run.usage.outputTokens} / 缓存 ${run.usage.cachedTokens}`));
  else response.diagnosticsBody.append(element("p", "", "用量统计：模型网关未提供"));
  const operations = element("ul");
  for (const op of (run.operations || []).slice(0, 250)) {
    if (op.name?.startsWith("__")) continue;
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
  if (response.finalReady) void checkVideoOffer(response);
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
function htmlAttribute(value) {
  return String(value).replace(/&/g, "&amp;").replace(/"/g, "&quot;")
    .replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function downloadHtmlReport(html) {
  const url = URL.createObjectURL(new Blob([html], { type: "text/html;charset=utf-8" }));
  const link = element("a");
  link.href = url;
  link.download = `Qiqi-分析报告-${new Date().toISOString().slice(0, 10)}.html`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function portableHtmlReport(response) {
  const views = response.dataViews.cloneNode(true);
  views.querySelectorAll(".data-view-card").forEach(card => {
    const queryId = card.dataset.queryId;
    const renderer = response.dataViewRenderers?.get(queryId);
    if (!renderer) return;
    card.open = true;
    card.querySelector(".data-view-tabs")?.remove();
    card.querySelector(".data-view-stage")?.remove();
    card.querySelector(".chart-explorer")?.remove();
    card.querySelector(".data-analysis-chart")?.remove();
    card.querySelector(".data-view-actions")?.remove();
    const modes = element("div", "export-view-modes");
    for (const mode of renderer.modes) {
      const section = element("details", "export-view-mode");
      section.open = mode === "概览" || mode === "表格";
      section.append(element("summary", "", mode));
      const stage = element("div", "data-view-stage");
      renderer.paint(mode, stage);
      section.append(stage);
      modes.append(section);
    }
    card.querySelector(".data-view-inner")?.append(modes);
  });
  const figures = [];
  for (const chart of response.charts.values()) {
    let source = chart.source;
    if (chart.option && window.echarts) {
      try { source = NomanCharts.image(chart.option); } catch { /* Fall back to the saved original PNG. */ }
    }
    if (source.startsWith("/api/charts/")) {
      const result = await request(source, { headers: { "X-Qiqi-User": response.username } });
      if (!result.ok) throw new Error("Chart unavailable");
      const blob = await result.blob();
      source = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result)); reader.onerror = reject;
        reader.readAsDataURL(blob);
      });
    }
    const markup = `<figure><figcaption>${htmlAttribute(chart.title)}</figcaption><img src="${htmlAttribute(source)}" alt="${htmlAttribute(chart.title)}"><small>查询证据：${htmlAttribute(chart.queryId)}</small></figure>`;
    const card = [...views.querySelectorAll(".data-view-card")].find(card => card.dataset.queryId === chart.queryId);
    if (card) card.querySelector(".data-view-inner").insertAdjacentHTML("afterbegin", markup);
    else figures.push(markup);
  }
  const sources = [...response.webSources.values()].map(source =>
    `<li><a href="${htmlAttribute(source.url)}" rel="noopener noreferrer">${htmlAttribute(source.title)}</a></li>`).join("");
  const style = `*{box-sizing:border-box}body{margin:0;background:#f6f7fb;color:#26302e;font:16px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{max-width:1040px;margin:42px auto;padding:36px 42px;background:white;border:1px solid #e4e7ef;border-radius:22px;box-shadow:0 18px 60px #2024470c}header{border-bottom:1px solid #e6e9ef;margin-bottom:28px;padding-bottom:20px}header h1{margin:0;font-size:27px}header p,small{color:#777f91}.report-content h1,.report-content h2,.report-content h3{line-height:1.35}.report-content table,.data-table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #e5e9ef;padding:9px 12px;text-align:left}.table-wrap,.data-table-wrap{overflow:auto}.report-content pre{overflow:auto;background:#f5f6fa;padding:16px;border-radius:10px}figure{margin:28px 0;padding:18px;border:1px solid #e5e8ef;border-radius:16px}figure img{display:block;max-width:100%;margin:14px auto}figure small{display:block}.data-view-card{border:1px solid #e6e9ef;border-radius:16px;margin:20px 0;padding:16px}.data-view-title{font-weight:650}.data-view-intro{display:flex;gap:16px;color:#777f91;font-size:13px}.data-kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:18px 0}.data-kpi{background:#f6f7fd;border-radius:12px;padding:14px}.data-kpi span,.data-kpi strong{display:block}.data-kpi span{font-size:12px;color:#777f91}.data-kpi strong{font-size:23px}.data-view-note,.data-view-warning{font-size:12px;color:#777f91}@media(max-width:600px){main{margin:0;padding:20px;border:0;border-radius:0}}`;
  const exportStyle = `.export-view-mode{border-top:1px solid #e9ebf1;padding:12px 0}.export-view-mode summary{cursor:pointer;color:#454f71;font-weight:600}.export-view-mode .data-view-stage{padding:8px 0 12px}.data-view-warning{color:#a06d30}@media print{body{background:white}main{margin:0;max-width:none;padding:0;border:0;box-shadow:none}.export-view-mode{break-inside:avoid}.export-view-mode:not([open]){display:none}}`;
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: https: http:"><title>Qiqi 数据分析报告</title><style>${style}${exportStyle}</style></head><body><main><header><h1>数据分析报告</h1><p>Qiqi · ${htmlAttribute(new Date().toLocaleDateString("zh-CN"))}</p></header><section class="report-content">${response.resultConclusionBody.innerHTML}${figures.join("")}${views.innerHTML}${response.reportBody.innerHTML}</section>${sources ? `<footer><h2>网络来源</h2><ul>${sources}</ul></footer>` : ""}</main></body></html>`;
}
function reportMarkdown(response) {
  return response.text + [...response.charts.values()].map(chart =>
    `\n\n![${chart.title.replace(/[\[\]\r\n]/g, " ")}](${chart.source.replace(/\(/g, "%28").replace(/\)/g, "%29")})\n\n查询证据：${chart.queryId}`,
  ).join("") + (response.webSources.size ? "\n\n网络来源：\n" + [...response.webSources.values()].map(source =>
    `- [${source.title.replace(/[\[\]\r\n]/g, " ")}](${source.url.replace(/\(/g, "%28").replace(/\)/g, "%29")})`).join("\n") : "");
}
const executionLabels = { QUEUED: "准备参数", RUNNING: "执行中", SUCCEEDED: "完成", FAILED: "未成功", CANCELLED: "已停止", INCOMPLETE: "未完成" };
function executionProgress(execution) {
  return execution.status === "PARTIAL" && execution.finalNarrationId
    ? "结果已生成 · 可查看失败记录" : execution.progress;
}
function renderExecutionSequence(response, execution) {
  const narrations = new Map((execution.narrations || [])
    .filter(item => item?.kind === "text" && typeof item.id === "string")
    .map(item => [item.id, item]));
  const nodes = [];
  const seen = new Set();
  const appendStep = (kind, id) => {
    if (typeof id !== "string" || seen.has(`${kind}:${id}`)) return;
    seen.add(`${kind}:${id}`);
    if (kind === "tool") {
      const tool = response.toolDetails.get(id);
      if (tool) nodes.push(tool.item);
    } else if (kind === "text" && id !== execution.finalNarrationId) {
      const narration = narrations.get(id);
      if (!narration?.content) return;
      let view = response.narrationDetails.get(id);
      if (!view) {
        const row = timelineRow("text", "narration-step");
        const label = element("div", "narration-label", "阶段说明");
        const body = element("div", "narration-content markdown-body");
        row.body.append(label, body);
        view = { item: row.item, label, body, content: null };
        response.narrationDetails.set(id, view);
      }
      view.label.textContent = execution.status === "RUNNING" && execution.steps?.at(-1)?.id === id
        ? "正在输出" : "阶段说明";
      if (view.content !== narration.content) {
        paintMarkdown(view.body, narration.content);
        view.content = narration.content;
      }
      nodes.push(view.item);
    }
  };
  for (const step of execution.steps || []) appendStep(step.kind, step.id);
  if (!execution.steps?.length)
    for (const narration of narrations.values()) appendStep("text", narration.id);
  for (const tool of execution.tools || []) appendStep("tool", tool.id);
  const previous = [...response.executionList.childNodes];
  if (previous.length !== nodes.length || previous.some((node, index) => node !== nodes[index]))
    response.executionList.replaceChildren(...nodes);
  for (const [id] of response.narrationDetails)
    if (!narrations.has(id) || id === execution.finalNarrationId) response.narrationDetails.delete(id);
  if (!response.processInitialized && execution.status === "RUNNING" && nodes.length) {
    response.executionDetails.open = true;
    response.processInitialized = true;
  }
  if (execution.status !== "RUNNING" && !response.processTouched) response.executionDetails.open = false;
}
function executionCounts(tools) {
  return tools.reduce((counts, tool) => {
    counts.total++;
    if (["FAILED", "INCOMPLETE", "CANCELLED"].includes(tool.status)) counts.failed++;
    else if (["RUNNING", "QUEUED"].includes(tool.status)) counts.running++;
    else if (tool.status === "SUCCEEDED") counts.succeeded++;
    return counts;
  }, { total: 0, failed: 0, running: 0, succeeded: 0 });
}
function applyExecution(response, execution, restore = false) {
  if (!execution || execution.version !== 1 || execution.conversationId !== response.conversationId
      || !Array.isArray(execution.tools) || !Array.isArray(execution.todos)
      || (response.diagnostics?.runId && response.diagnostics.runId !== execution.runId)) return;
  if (response.execution?.runId === execution.runId && response.execution.revision > execution.revision) return;
  response.execution = execution;
  const ids = new Set(execution.tools.map(tool => tool.id));
  for (const [id, view] of response.toolDetails) if (!ids.has(id)) {
    view.item.remove(); response.toolDetails.delete(id); response.tools.delete(id);
  }
  for (const tool of execution.tools.slice(0, 128)) {
    if (typeof tool.id !== "string" || tool.name === "todoWrite" || tool.name?.startsWith("__")) continue;
    let view = response.toolDetails.get(tool.id);
    if (!view) {
      const row = timelineRow(["FAILED", "INCOMPLETE", "CANCELLED"].includes(tool.status) ? "failed" : tool.status === "RUNNING" ? "running" : "tool", "tool-step");
      const title = element("div", "timeline-tool-name");
      const details = element("details", "tool-detail");
      const summary = element("summary", "", "查看详情");
      const input = element("pre", "tool-payload");
      const output = element("pre", "tool-payload");
      const inputLabel = element("div", "tool-payload-label", "调用参数 · 已脱敏");
      const outputLabel = element("div", "tool-payload-label", "返回结果 · 摘要");
      details.append(summary, inputLabel, input, outputLabel, output);
      row.body.append(title, details);
      view = { item: row.item, dot: row.dot, title, input, inputLabel, output, details };
      response.toolDetails.set(tool.id, view);
      response.tools.set(tool.id, row.item);
    }
    const duration = (Math.max(0, Number(tool.durationMs) || 0) / 1000).toFixed(2);
    const failed = ["FAILED", "INCOMPLETE", "CANCELLED"].includes(tool.status);
    view.title.textContent = `${toolNames[tool.name] || tool.name} · ${executionLabels[tool.status] || tool.status} · ${duration} 秒`;
    view.item.classList.toggle("failed", failed);
    view.dot.className = `execution-dot ${failed ? "failed" : tool.status === "RUNNING" || tool.status === "QUEUED" ? "running" : "tool"}`;
    view.item.dataset.status = tool.status;
    view.input.textContent = tool.arguments || (["QUEUED", "RUNNING"].includes(tool.status) ? "正在接收参数…" : "");
    view.input.hidden = !view.input.textContent;
    view.inputLabel.hidden = view.input.hidden;
    view.output.textContent = tool.result || (["QUEUED", "RUNNING"].includes(tool.status) ? "等待工具返回…" : "未返回文本");
  }
  renderExecutionSequence(response, execution);
  const visibleTools = execution.tools.filter((tool) => tool.name !== "todoWrite" && !tool.name?.startsWith("__"));
  const counts = executionCounts(visibleTools);
  const narrationCount = response.executionList.querySelectorAll(".narration-step").length;
  response.executionSummary.textContent = `分析过程 · ${narrationCount ? `${narrationCount} 段说明 · ` : ""}${counts.total} 次执行` +
    (counts.succeeded ? ` · ${counts.succeeded} 项完成` : "") +
    (counts.running ? ` · ${counts.running} 项进行中` : "") +
    (counts.failed ? ` · ${counts.failed} 项未成功` : "") +
    (execution.truncated ? " · 部分记录已省略" : "");
  response.todoArea.hidden = !execution.todos.length;
  const completed = execution.todos.filter(todo => todo.status === "completed").length;
  const inferred = execution.todos.some(todo => todo.completionSource === "final_report");
  const title = element("summary", "", `分析计划 · ${completed}/${execution.todos.length}${inferred ? " · 已按报告收尾" : ""}`);
  response.progressCount.textContent = execution.todos.length
    ? `${completed}/${execution.todos.length} 已完成`
    : execution.status === "RUNNING" ? "分析中" : "已结束";
  const list = element("ol", "todo-list");
  for (const todo of execution.todos.slice(0, 20)) {
    const interrupted = todo.status === "in_progress" && execution.status !== "RUNNING";
    const label = todo.completionSource === "final_report" ? "报告收尾" : todo.status === "completed" ? "已完成" : interrupted ? "未完成" : todo.status === "in_progress" ? "进行中" : "待处理";
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
  if (execution.analysisPlan && execution.status === "AWAITING_CONFIRMATION") {
    response.pendingPlan = execution.analysisPlan;
    response.awaitingPlan = true;
    renderPlanCard(response, execution.analysisPlan);
  }
  if (execution.status === "RUNNING") response.progress.textContent = execution.progress || "正在分析…";
  else response.progress.classList.remove("busy");
  if (restore) {
    // Never re-run a model to restore a page: the server journal is authoritative.
    const canRestoreFinal = Boolean(execution.finalNarrationId) ||
      (execution.status === "SUCCEEDED" && Boolean(execution.text));
    if (canRestoreFinal) {
      response.text = execution.text || response.text;
      response.restoredComplete = true;
      render(response);
    }
    response.progress.textContent = execution.status === "AWAITING_CONFIRMATION"
      ? "我将在你确认以后继续" : executionProgress(execution);
    response.actions.hidden = !response.restoredComplete;
    if (["FAILED"].includes(execution.status)) {
      setNotice(response, execution.errorCode === "MAX_ITERATIONS"
        ? "已达到分析轮数上限，可继续完成剩余计划。" : `运行未完成（${execution.errorCode || execution.status}），已恢复保存的进度。`);
    } else if (execution.errorCode === "PLAN_INCOMPLETE") {
      setNotice(response, "分析计划仍有未完成项，报告与证据已保留。", "note");
    }
  }
  syncPresentation(response);
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
      setNotice(response, `已达到分析轮数上限（${data.maxIters || "配置值"}），已保留计划与证据，可继续完成剩余任务。`);
      break;
    case "RUN_START":
      showDiagnostics(response, data.run);
      break;
    case "PLAN_CARD":
      response.pendingPlan = data;
      response.awaitingPlan = true;
      renderPlanCard(response, data);
      break;
    case "RUN_END":
      response.runEnded = true;
      if (data.run?.status === "AWAITING_CONFIRMATION") response.awaitingPlan = true;
      showDiagnostics(response, data.run);
      response.done = true;
      if (data.run?.status === "FAILED" || data.run?.status === "CANCELLED") {
        setNotice(response, data.run.errorCode === "MAX_ITERATIONS"
          ? "已达到分析轮数上限，可继续完成剩余计划。"
          : `本次运行未完成（${data.run.errorCode || data.run.status}），请查看运行详情。`);
      } else if (data.run?.errorCode === "MAX_ITERATIONS") {
        setNotice(response, "已达到分析轮数上限，可继续完成剩余计划。", "note");
      } else if (data.run?.errorCode === "PLAN_INCOMPLETE") {
        setNotice(response, "分析计划仍有未完成项，报告与证据已保留。", "note");
      }
      revealCharts(response);
      syncPresentation(response);
      break;
    case "TEXT_BLOCK_DELTA":
      if (response.replyId && data.replyId !== response.replyId) {
        response.text = "";
        response.visibleLength = 0;
      }
      response.replyId = data.replyId;
      response.text += data.delta || "";
      response.progress.textContent = "正在生成分析…";
      scheduleRender(response);
      break;
    case "TOOL_CALL_START": {
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
        step.classList.toggle("failed", failed);
        const dot = step.querySelector(".execution-dot");
        if (dot) dot.className = `execution-dot ${failed ? "failed" : "tool"}`;
      }
      if (response.results.has(data.toolCallId))
        addEvidence(response, response.results.get(data.toolCallId));
      break;
    }
    case "ERROR":
      setNotice(response, data.message || "分析失败，请稍后重试。");
      break;
    case "AGENT_END":
      response.done = true;
      response.agentEnded = true;
      revealCharts(response);
      syncPresentation(response);
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
function renderPlanCard(response, plan) {
  if (!response.planHost || !plan) return;
  state.planResponse = response;
  response.planHost.hidden = false;
  response.planHost.replaceChildren();
  response.planHost.append(element("h3", "plan-card-title", plan.title || "分析计划"));
  const sections = element("ol", "plan-sections");
  for (const section of plan.sections || []) {
    const item = element("li", "plan-section");
    item.append(element("strong", "", section.title || ""));
    const points = element("ul", "plan-points");
    for (const point of section.items || []) points.append(element("li", "", point));
    item.append(points);
    sections.append(item);
  }
  response.planHost.append(sections);
  const outputs = element("div", "plan-outputs");
  outputs.append(element("h4", "", "最终输出"));
  for (const output of plan.deliverables || []) {
    const row = element("div", "plan-output");
    row.append(element("strong", "", output.title || ""), element("span", "", output.detail || ""));
    outputs.append(row);
  }
  response.planHost.append(outputs);
  const actions = element("div", "plan-actions");
  const revise = element("button", "plan-revise", "修改任务");
  revise.type = "button";
  const start = element("button", "plan-start", "开始任务");
  start.type = "button";
  const editor = element("textarea", "plan-editor");
  editor.hidden = true;
  editor.placeholder = "说明要改的范围，例如只要行业、不要地图";
  const sendRevision = element("button", "plan-revise", "提交修改");
  sendRevision.type = "button";
  sendRevision.hidden = true;
  revise.addEventListener("click", () => {
    editor.hidden = false;
    sendRevision.hidden = false;
    editor.focus();
  });
  sendRevision.addEventListener("click", () => continuePlan(response, false, editor.value.trim()));
  start.addEventListener("click", () => continuePlan(response, true, ""));
  actions.append(revise, start);
  response.planHost.append(actions, editor, sendRevision);
  response.progress.textContent = "我将在你确认以后继续";
}
async function continuePlan(response, confirmed, feedback) {
  if (!response?.pendingPlan || currentRun()) return;
  if (!confirmed && !feedback) return;
  const chat = activeChat();
  const controller = new AbortController();
  response.awaitingPlan = false;
  response.done = false;
  response.runEnded = false;
  response.pending = true;
  if (confirmed && response.planHost) response.planHost.hidden = true;
  response.progress.classList.add("busy");
  response.progress.textContent = confirmed ? "正在按计划查数…" : "正在按修改意见重写计划…";
  state.runs.set(chat.id, { controller, response });
  refreshControls();
  try {
    const result = await request("/api/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Qiqi-User": user.value },
      body: JSON.stringify({
        query: confirmed ? "开始任务" : feedback,
        conversationId: response.conversationId,
        online: false,
        analysisMode: response.analysisMode || "AUTO",
        plan: { toolCallId: response.pendingPlan.toolCallId, confirmed, feedback },
      }),
      signal: controller.signal,
    });
    if (!result.ok) throw new Error(`请求失败（${result.status}）`);
    if (confirmed) response.planHost.hidden = true;
    await consumeStream(result.body, response);
  } catch (error) {
    if (error.name !== "AbortError") {
      response.failed = true;
      response.error.hidden = false;
      response.error.textContent = error.message || "连接失败，请稍后重试。";
    }
  } finally {
    state.runs.delete(chat.id);
    response.progress.classList.remove("busy");
    if (response.awaitingPlan) response.progress.textContent = "我将在你确认以后继续";
    else if (!response.failed) response.progress.textContent = "分析完成";
    refreshControls();
  }
}
async function send(query) {
  if (state.planResponse?.awaitingPlan && state.planResponse.card?.isConnected) {
    return continuePlan(state.planResponse, false, query.trim());
  }
  if (currentRun() || !state.configured || state.historyLoading || !query.trim()) return;
  const analysisMode = $("#analysis-mode").value;
  if (state.attachment?.conversationId === state.conversationId) {
    query += `\n\n已上传数据文件：${state.attachment.name}；fileId=${state.attachment.fileId}。请先用 analyze_file 预览，统计使用完整文件。`;
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
    analysisMode,
    steps: [],
    evidence: [],
  };
  chat.turns.push(turn);
  saveHistory();
  renderHistory();
  refreshControls();
  showWorkspacePage(null);
  messages.append(element("article", "message user", query));
  const response = createResponse(query, turn.time, chat.runtimeId);
  response.analysisMode = analysisMode;
  response.savedTurn = turn;
  state.runs.set(chat.id, { controller, response });
  chat.draft = "";
  renderHistory();
  refreshControls();
  input.value = "";
  $("#analysis-mode").value = "AUTO";
  syncModePicker();
  closeComposerMenus();
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
      body: JSON.stringify({ query, conversationId: chat.runtimeId, online, analysisMode }),
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
    if (!response.awaitingPlan && (!response.done || (response.diagnostics && !response.runEnded)) && !response.failed)
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
        : response.awaitingPlan
          ? "我将在你确认以后继续"
          : "分析完成";
    response.summary.textContent = `分析过程 · ${response.tools.size} 次工具调用${stopped || response.failed ? " · 已中断" : ""}`;
    response.actions.hidden = !response.text;
    state.runs.delete(chat.id);
    if ((response.failed || stopped) && !response.awaitingPlan) {
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
function setHistoryExpanded(expanded) {
  $(".sidebar").classList.toggle("history-expanded", expanded);
  $("#history-toggle").setAttribute("aria-expanded", String(expanded));
}
$("#history-toggle").addEventListener("click", () => {
  setHistoryExpanded($("#history-toggle").getAttribute("aria-expanded") !== "true");
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
async function uploadSelectedFile(event, fromCatalog = false) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  if (!state.workspaceEnabled || !/\.(csv|xlsx|xls)$/i.test(file.name) || file.size > 2 * 1024 * 1024) {
    toast("请上传不超过 2 MB 的 CSV、XLSX 或 XLS 文件"); return;
  }
  const conversationId = state.conversationId, username = user.value;
  state.attachment = null;
  $("#file-status").textContent = "正在读取文件…";
  try {
    const payload = { name: file.name, conversationId };
    if (/\.csv$/i.test(file.name)) payload.content = await file.text();
    else {
      const bytes = new Uint8Array(await file.arrayBuffer());
      let binary = "";
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      payload.base64 = btoa(binary);
    }
    const result = await request("/api/files", { method: "POST", headers: { "Content-Type": "application/json", "X-Qiqi-User": username },
      body: JSON.stringify(payload) });
    if (!result.ok) { const error = await result.json(); throw new Error(error.error || "文件上传失败"); }
    const data = await result.json();
    if (state.conversationId !== conversationId || user.value !== username) return;
    state.attachment = { ...data, conversationId };
    $("#file-status").textContent = `${data.name} · ${data.totalRows} 行 · ${data.columns.join("、")}`;
    if (fromCatalog) {
      catalog.tab = "files"; catalog.selected = data.fileId;
      await loadCatalog(); toast("文件已上传，可以查看字段或去分析");
    } else {
      input.value ||= "请分析这个文件，先介绍数据，再按合适的维度汇总。";
      resizeInput(); refreshControls();
    }
  } catch (error) {
    $("#file-status").textContent = "";
    toast(error.message || "文件上传失败");
  }
}
$("#file-upload").addEventListener("change", event => uploadSelectedFile(event));
$("#data-center-upload").addEventListener("change", event => uploadSelectedFile(event, true));
$("#remove-file").addEventListener("click", () => {
  state.attachment = null;
  $("#file-status").textContent = "";
  refreshControls();
});
loadMeta().catch(() => {
  $("#notice").textContent = "无法连接服务，请确认服务已启动后刷新页面。";
  $("#notice").hidden = false;
  $("#status").textContent = "连接失败";
  $("#connection-label").textContent = "服务未连接";
});
