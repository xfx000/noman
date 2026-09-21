const { JSDOM } = require("jsdom");
const { readFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const root =
  require("node:path").resolve(__dirname, "../../main/resources/static") + "/";
const events = (list) =>
  list
    .map(
      (e) => "event:" + e.type + "\r\ndata:" + JSON.stringify(e) + "\r\n\r\n",
    )
    .join("");
const ev = (type, data = {}) => ({ type, data });
async function setup(handler, stored = {}, meta = {}) {
  const dom = new JSDOM(readFileSync(root + "index.html", "utf8"), {
    runScripts: "outside-only",
    pretendToBeVisual: true,
    url: "http://localhost/",
  });
  const w = dom.window;
  for (const [key, value] of Object.entries(stored))
    w.localStorage.setItem(key, value);
  w.HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  w.HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  w.TextDecoder = TextDecoder;
  w.matchMedia = (query) => ({
    matches: !query.includes("prefers-reduced-motion"),
  });
  w.fetch = async (url, options) =>
    url === "/api/meta"
      ? {
          ok: true,
          json: async () => ({
            modelConfigured: true,
            ...meta,
            demoUsers: [
              { username: "admin", displayName: "Admin", dataScope: "ALL" },
              {
                username: "alice",
                displayName: "Alice",
                dataScope: "DEPARTMENT",
              },
            ],
          }),
        }
      : handler(options, url);
  for (const f of ["vendor/marked.umd.js", "vendor/purify.min.js", "app.js"])
    w.eval(
      readFileSync(root + f, "utf8") +
        (f === "app.js" ? "\nwindow.send = send;" : ""),
    );
  await new Promise((r) => setTimeout(r, 10));
  return w;
}
function streamed(text) {
  const bytes = new TextEncoder().encode(text);
  return new Response(
    new ReadableStream({
      start(c) {
        for (let i = 0; i < bytes.length; i += 3)
          c.enqueue(bytes.slice(i, i + 3));
        c.close();
      },
    }),
  );
}
(async () => {
  let attachmentRequest;
  const fileUpload = await setup((options, url) => {
    if (url === "/api/history") return { ok: true, json: async () => ({ revision: options.method === "PUT" ? JSON.parse(options.body).revision + 1 : 0, data: null }) };
    if (url === "/api/files") {
      const data = JSON.parse(options.body);
      assert.equal(options.headers["X-Qiqi-User"], "admin");
      assert.equal(data.name, "sample.csv");
      return { ok: true, json: async () => ({ fileId: "test-file", name: data.name, totalRows: 2, columns: ["area", "amount"] }) };
    }
    attachmentRequest = JSON.parse(options.body);
    return streamed(events([ev("TEXT_BLOCK_DELTA", { replyId: "file", delta: "文件已分析" }), ev("AGENT_END")]));
  }, {}, { workspaceEnabled: true });
  const fileInput = fileUpload.document.querySelector("#file-upload");
  Object.defineProperty(fileInput, "files", { value: [{ name: "sample.csv", size: 40, text: async () => "area,amount\nA,1\nB,2" }] });
  fileInput.dispatchEvent(new fileUpload.Event("change"));
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.match(fileUpload.document.querySelector("#file-status").textContent, /2 行/);
  await fileUpload.send("汇总文件");
  assert.match(attachmentRequest.query, /fileId=test-file/);
  assert.equal(fileUpload.document.querySelector("#file-status").textContent, "");
  fileUpload.close();
  console.log("PASS CSV attachment upload, preview metadata and scoped analysis request");
  let serverHistory = { revision: 2, data: { chats: [{ id: "server-chat", runtimeId: "server-session", title: "服务端对话", updatedAt: 1,
    turns: [{ query: "old", text: "服务端保存的回答", status: "分析完成", steps: [], evidence: [] }] }], activeId: "server-chat" } };
  let savedRequests = 0;
  const persistent = await setup((options, url) => {
    if (url === "/api/history") {
      if (options.method === "PUT") {
        const input = JSON.parse(options.body);
        assert.equal(input.revision, serverHistory.revision);
        serverHistory = { revision: input.revision + 1, data: input.data };
        savedRequests++;
      }
      return { ok: true, json: async () => JSON.parse(JSON.stringify(serverHistory)) };
    }
    return streamed(events([ev("TEXT_BLOCK_DELTA", { replyId: "persist", delta: "继续的回答" }), ev("AGENT_END")]));
  }, {}, { workspaceEnabled: true });
  assert.match(persistent.document.querySelector(".report").textContent, /服务端保存/);
  await persistent.send("continue");
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(savedRequests > 0);
  assert.equal(serverHistory.data.chats[0].runtimeId, "server-session");
  assert.equal(serverHistory.data.chats[0].turns[1].text, "继续的回答");
  persistent.close();
  console.log("PASS server history restore, serialized versioned saves and stable conversation id");
  const sampleRun = { runId: "12345678-1234-1234-1234-123456789abc", status: "PARTIAL", durationMs: 2100,
    usage: { inputTokens: 42, outputTokens: 8, cachedTokens: 3 },
    operations: [{ id: "search", kind: "tool", name: "web_search", status: "FAILED", durationMs: 120, errorCode: "WEB_TIMEOUT" }] };
  const requests = [];
  const research = await setup((options) => {
    requests.push(JSON.parse(options.body));
    return streamed(events([
      ev("RUN_START", { run: { ...sampleRun, status: "RUNNING" } }),
      ev("TOOL_RESULT_END", { toolCallName: "web_search", state: "success", metadata: { webSearch: { sources: [
        { title: "Public <script>bad</script>", url: "https://example.com/report" },
        { title: "Unsafe", url: "javascript:alert(1)" }, { title: "Unsafe PNG", url: "data:image/png;base64,AAAA" },
      ] } } }),
      ev("TEXT_BLOCK_DELTA", { delta: "公开资料供参考。", replyId: "a" }), ev("AGENT_END"), ev("RUN_END", { run: sampleRun }),
    ]));
  }, {}, { webSearchEnabled: true });
  research.document.querySelector("#online-search").checked = true;
  await research.send("查询行业背景");
  assert.equal(requests[0].online, true);
  assert.equal(research.document.querySelector("#online-search").checked, false);
  assert.equal(research.document.querySelectorAll(".web-sources a").length, 1);
  assert.equal(research.document.querySelector(".web-sources script"), null);
  assert.match(research.document.querySelector(".run-diagnostics").textContent, /WEB_TIMEOUT/);
  assert.match(research.document.querySelector(".run-diagnostics").textContent, /输入 42/);
  const researchHistory = research.localStorage.getItem("qiqi.conversations.v1.admin");
  research.close();
  const restoredResearch = await setup(() => streamed(events([])), { "qiqi.conversations.v1.admin": researchHistory });
  assert.equal(restoredResearch.document.querySelectorAll(".web-sources a").length, 1);
  assert.match(restoredResearch.document.querySelector(".run-diagnostics").textContent, /含工具失败/);
  assert.equal(restoredResearch.document.querySelector("#online-search").disabled, true);
  restoredResearch.close();
  const exhausted = await setup(() => streamed(events([ev("AGENT_END"), ev("RUN_END", { run: { ...sampleRun, status: "INCOMPLETE", errorCode: "MAX_ITERATIONS" } })])));
  await exhausted.send("complex");
  assert.match(exhausted.document.querySelector(".response-error").textContent, /MAX_ITERATIONS/);
  exhausted.close();
  console.log("PASS per-turn online opt-in, source safety, diagnostics, usage, history and incomplete runs");
  const chart = { id: "chart-1", queryId: "query-1", title: "收入 <script>bad</script>", type: "bar", source: "https://storage.example/chart.png" };
  const chartEvents = [
    ev("TOOL_CALL_START", { toolCallId: "chart-call", toolCallName: "generate_chart" }),
    ev("TOOL_RESULT_END", { toolCallId: "chart-call", toolCallName: "generate_chart", state: "success", metadata: { chart } }),
    ev("TOOL_RESULT_END", { toolCallId: "chart-call", toolCallName: "generate_chart", state: "success", metadata: { chart } }),
    ev("TEXT_BLOCK_DELTA", { replyId: "chart", delta: "已生成图表。" }), ev("AGENT_END"),
  ];
  const charts = await setup(() => streamed(events(chartEvents)));
  await charts.send("画收入图");
  assert.equal(charts.document.querySelectorAll(".chart-card").length, 1);
  assert.equal(charts.document.querySelector(".chart-image").alt, chart.title);
  assert.equal(charts.document.querySelector(".chart-card script"), null);
  assert.match(charts.document.querySelector(".chart-evidence").textContent, /query-1/);
  const history = charts.localStorage.getItem("qiqi.conversations.v1.admin");
  charts.close();
  const replay = await setup(() => streamed(events([])), { "qiqi.conversations.v1.admin": history });
  assert.equal(replay.document.querySelectorAll(".chart-card").length, 1);
  replay.document.querySelector(".chart-image").dispatchEvent(new replay.Event("error"));
  assert.equal(replay.document.querySelector(".chart-error").hidden, false);
  replay.close();
  for (const source of ["javascript:alert(1)", "data:image/svg+xml,<svg onload=alert(1)>", "https://user:pass@example.com/x"]) {
    const unsafe = await setup(() => streamed(events([
      ev("TOOL_RESULT_END", { toolCallName: "generate_chart", state: "success", metadata: { chart: { ...chart, source } } }), ev("AGENT_END"),
    ])));
    await unsafe.send("检查图表来源");
    assert.equal(unsafe.document.querySelectorAll(".chart-card").length, 0);
    unsafe.close();
  }
  const outage = await setup(() => streamed(events([
    ev("TOOL_RESULT_TEXT_DELTA", { toolCallName: "generate_chart", toolCallId: "bad", delta: "图表服务超时" }),
    ev("TOOL_RESULT_END", { toolCallName: "generate_chart", toolCallId: "bad", state: "error" }),
    ev("TEXT_BLOCK_DELTA", { replyId: "fallback", delta: "查询仍然成功。" }), ev("AGENT_END"),
  ])));
  await outage.send("画图");
  assert.match(outage.document.querySelector(".chart-error").textContent, /超时/);
  assert.match(outage.document.querySelector(".report").textContent, /查询仍然成功/);
  assert.equal(outage.document.querySelectorAll(".chart-card").length, 0);
  outage.close();
  console.log("PASS chart artifacts, duplicate events, history replay, image failures, unsafe sources and MCP outage fallback");
  const typingText = "逐字呈现中文与 emoji 🌿，保持完整。".repeat(10);
  const typing = await setup(() =>
    streamed(
      events([
        ev("TEXT_BLOCK_DELTA", { replyId: "typing", delta: typingText }),
        ev("AGENT_END"),
      ]),
    ),
  );
  const td = typing.document;
  assert.equal(td.documentElement.dataset.theme, "white");
  td.querySelector("#open-settings").click();
  assert.equal(td.querySelector("#settings-page").hidden, false);
  const sage = td.querySelector('[name="theme"][value="sage"]');
  sage.checked = true;
  sage.dispatchEvent(new typing.Event("change"));
  assert.equal(td.documentElement.dataset.theme, "sage");
  const settings = typing.localStorage.getItem("qiqi.preferences.v1");
  assert.equal(JSON.parse(settings).theme, "sage");
  td.querySelector("#close-settings").click();
  const typingRun = typing.send("打字测试");
  await new Promise((r) => setTimeout(r, 110));
  const partial = td.querySelector(".report").textContent;
  assert.ok(partial.length > 0 && partial.length < typingText.length);
  assert.ok(!partial.includes("\ufffd"));
  assert.equal(td.querySelector("#stop").hidden, false);
  td.querySelector("#stop").click();
  await typingRun;
  assert.equal(td.querySelector(".report").textContent.trim(), typingText);
  assert.equal(td.querySelector(".report").classList.contains("typing"), false);
  assert.match(td.querySelector(".response-status").textContent, /已停止/);
  typing.close();
  const themeRestored = await setup(
    () =>
      streamed(
        events([
          ev("TEXT_BLOCK_DELTA", { replyId: "a", delta: typingText }),
          ev("AGENT_END"),
        ]),
      ),
    { "qiqi.preferences.v1": settings },
  );
  assert.equal(themeRestored.document.documentElement.dataset.theme, "sage");
  themeRestored.document.querySelector("#typing-enabled").click();
  assert.equal(
    themeRestored.document.querySelector("#typing-speed").disabled,
    true,
  );
  await themeRestored.send("关闭打字效果");
  assert.equal(
    themeRestored.document.querySelector(".report").textContent.trim(),
    typingText,
  );
  themeRestored.close();
  console.log(
    "PASS theme persistence, settings navigation, progressive Unicode output, stop while draining and typing toggle",
  );
  const settingsData = {
    restartRequired: false,
    model: {
      provider: "openai",
      baseUrl: "https://example.com/v1",
      name: "test-model",
      keyConfigured: true,
    },
    database: {
      kind: "H2 内存数据库",
      location: "本机",
      maxRows: "200",
      timeoutSeconds: "10",
    },
  };
  let savedSettings;
  const configWindow = await setup((options) => {
    if (options.method === "POST") savedSettings = JSON.parse(options.body);
    return { ok: true, json: async () => settingsData };
  });
  const cd = configWindow.document;
  cd.querySelector("#open-settings").click();
  assert.equal(cd.querySelector(".sidebar").inert, true);
  cd.querySelector('[data-settings-tab="model"]').click();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(
    cd.querySelector("#settings-heading").textContent,
    "AI 模型与 Key",
  );
  assert.equal(cd.querySelector("#model-name").value, "test-model");
  assert.equal(cd.querySelector("#model-key").value, "");
  cd.querySelector("#model-key").value = "fake-test-key";
  cd.querySelector("#model-settings-form").dispatchEvent(
    new configWindow.Event("submit", { cancelable: true }),
  );
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(savedSettings.apiKey, "fake-test-key");
  assert.equal(cd.querySelector("#model-key").value, "");
  assert.ok(
    !JSON.stringify({ ...configWindow.localStorage }).includes("fake-test-key"),
  );
  cd.querySelector('[data-settings-tab="database"]').click();
  assert.equal(cd.querySelector("#database-max-rows").value, "200");
  cd.querySelector("#close-settings").click();
  assert.equal(cd.querySelector(".sidebar").inert, false);
  configWindow.close();
  console.log(
    "PASS settings categories, server values, write-only credentials and return navigation",
  );
  let calls = [];
  const w = await setup((options) => {
    calls.push(JSON.parse(options.body));
    return streamed(
      events([
        ev("TEXT_BLOCK_DELTA", {
          replyId: "a",
          delta:
            '## 分析报告\n\n**收入**：27,200\n\n| 部门 | 金额 |\n| --- | --- |\n| 北区 | 27200 |\n\n```sql\nSELECT SUM(total_amount) FROM sales_order;\n```\n\n<script>alert(1)</script><img src=x onerror=alert(1)><a href="javascript:alert(1)">bad</a><button id="send">bad</button>',
        }),
        ev("TOOL_CALL_START", {
          toolCallId: "t1",
          toolCallName: "execute_sql",
        }),
        ev("TOOL_RESULT_TEXT_DELTA", {
          toolCallId: "t1",
          toolCallName: "execute_sql",
          delta: JSON.stringify({
            queryId: "test-id",
            executedSql: "SELECT 1",
            rowCount: 1,
            durationMs: 2,
          }),
        }),
        ev("TOOL_RESULT_END", { toolCallId: "t1", state: "success" }),
        ev("AGENT_END"),
      ]),
    );
  });
  await w.send("测试");
  const d = w.document;
  assert.equal(d.querySelector(".report h2").textContent, "分析报告");
  assert.equal(d.querySelectorAll(".table-wrap table").length, 1);
  assert.equal(
    d.querySelector(".code-block code").textContent.trim(),
    "SELECT SUM(total_amount) FROM sales_order;",
  );
  assert.equal(
    d.querySelectorAll(
      '.report script,.report img,.report [onerror],.report a[href^="javascript:"]',
    ).length,
    0,
  );
  assert.equal(d.querySelectorAll("#send").length, 1);
  assert.equal(d.querySelectorAll(".evidence-item").length, 1);
  assert.equal(d.querySelector("#stop").hidden, true);
  assert.match(d.querySelector("#status").textContent, /分析完成/);
  const oldId = calls[0].conversationId;
  d.querySelector("#user").value = "alice";
  d.querySelector("#user").dispatchEvent(new w.Event("change"));
  assert.equal(d.querySelector("#messages").children.length, 0);
  await w.send("另一个身份");
  assert.notEqual(calls[1].conversationId, oldId);
  const adminSaved = w.localStorage.getItem("qiqi.conversations.v1.admin");
  const restoredCalls = [];
  const restored = await setup(
    (options) => {
      restoredCalls.push(JSON.parse(options.body));
      return streamed(
        events([
          ev("TEXT_BLOCK_DELTA", { replyId: "new", delta: "继续分析" }),
          ev("AGENT_END"),
        ]),
      );
    },
    { "qiqi.conversations.v1.admin": adminSaved },
  );
  assert.equal(restored.document.querySelectorAll(".history-row").length, 1);
  assert.equal(
    restored.document.querySelectorAll(".table-wrap table").length,
    1,
  );
  assert.equal(restored.document.querySelectorAll(".evidence-item").length, 1);
  assert.equal(
    restored.document.querySelector("#conversation-title").textContent,
    "测试",
  );
  await restored.send("继续");
  assert.equal(restoredCalls[0].conversationId, oldId);
  restored.document.querySelector("#new-chat").click();
  assert.equal(restored.document.querySelectorAll(".history-row").length, 1);
  await restored.send("第二个主题");
  assert.equal(restored.document.querySelectorAll(".history-row").length, 2);
  assert.notEqual(restoredCalls[1].conversationId, oldId);
  const original = [
    ...restored.document.querySelectorAll(".history-open"),
  ].find((button) => button.title === "测试");
  original.click();
  assert.equal(restored.document.querySelectorAll(".message.user").length, 2);

  restored.document
    .querySelector(".history-row.selected .history-delete")
    .click();
  assert.equal(restored.document.querySelector("#delete-dialog").open, true);
  restored.document.querySelector("#confirm-delete").click();
  assert.equal(
    JSON.parse(restored.localStorage.getItem("qiqi.conversations.v1.admin"))
      .chats.length,
    1,
  );
  assert.equal(restored.document.querySelector("#messages").children.length, 0);
  restored.document.querySelector("#user").value = "alice";
  restored.document
    .querySelector("#user")
    .dispatchEvent(new restored.Event("change"));
  assert.equal(restored.document.querySelectorAll(".history-row").length, 0);
  restored.close();
  const interrupted = JSON.parse(adminSaved);
  interrupted.chats[0].turns[0].pending = true;
  const recovered = await setup(
    () => {
      throw new Error("Must not request the model on restore");
    },
    { "qiqi.conversations.v1.admin": JSON.stringify(interrupted) },
  );
  assert.match(
    recovered.document.querySelector(".response-status").textContent,
    /中断/,
  );
  assert.notEqual(
    JSON.parse(recovered.localStorage.getItem("qiqi.conversations.v1.admin"))
      .chats[0].runtimeId,
    oldId,
  );
  recovered.close();
  console.log(
    "PASS persisted reports/evidence, resume session, new chat, switching, deletion, per-user history and interrupted-stream recovery",
  );
  w.close();
  console.log(
    "PASS Markdown tables/code, sanitization, fragmented UTF-8+CRLF SSE, evidence, identity isolation",
  );
  const f = await setup(
    () => new Response(JSON.stringify({ error: "测试错误" }), { status: 500 }),
  );
  await f.send("失败请求");
  assert.equal(
    f.document.querySelector(".response-error").textContent,
    "测试错误",
  );
  assert.equal(f.document.querySelector("#stop").hidden, true);
  assert.equal(f.document.querySelector("#user").disabled, false);
  assert.match(
    f.document.querySelector(".report-actions").textContent,
    /重新提问/,
  );
  f.close();
  console.log("PASS HTTP failure and retry controls");
  const early = await setup(() =>
    streamed(
      events([ev("TEXT_BLOCK_DELTA", { replyId: "a", delta: "部分内容" })]),
    ),
  );
  await early.send("中断");
  assert.match(
    early.document.querySelector(".response-error").textContent,
    /中断/,
  );
  early.close();
  console.log(
    "PASS incomplete stream preserves partial answer and reports failure",
  );
  let count = 0;
  const s = await setup((options) => {
    count++;
    return new Promise((resolve, reject) =>
      options.signal.addEventListener("abort", () =>
        reject(new s.DOMException("cancelled", "AbortError")),
      ),
    );
  });
  const run = s.send("等待");
  await s.send("重复发送");
  assert.equal(count, 1);
  s.document.querySelector("#stop").click();
  await run;
  assert.match(
    s.document.querySelector(".response-status").textContent,
    /已停止/,
  );
  assert.equal(s.document.querySelector("#new-chat").disabled, false);
  s.close();
  console.log("PASS duplicate submission guard and cancellation");
  const pending = [];
  const parallel = await setup(
    (options) =>
      new Promise((resolve, reject) => {
        pending.push({
          resolve,
          reject,
          body: JSON.parse(options.body),
          signal: options.signal,
        });
        options.signal.addEventListener("abort", () =>
          reject(new parallel.DOMException("cancelled", "AbortError")),
        );
      }),
  );
  const pd = parallel.document;
  const firstRun = parallel.send("第一个分析");
  assert.equal(pd.querySelector("#new-chat").disabled, false);
  pd.querySelector("#new-chat").click();
  assert.equal(pd.querySelector("#stop").hidden, true);
  const secondRun = parallel.send("第二个分析");
  assert.equal(pending.length, 2);
  assert.notEqual(
    pending[0].body.conversationId,
    pending[1].body.conversationId,
  );
  await parallel.send("不应重复提交");
  assert.equal(pending.length, 2);
  [...pd.querySelectorAll(".history-open")]
    .find((b) => b.title === "第一个分析")
    .click();
  assert.equal(pd.querySelector("#stop").hidden, false);
  pending[1].resolve(
    streamed(
      events([
        ev("TEXT_BLOCK_DELTA", { replyId: "second", delta: "第二份报告" }),
        ev("AGENT_END"),
      ]),
    ),
  );
  await secondRun;
  assert.doesNotMatch(pd.querySelector("#messages").textContent, /第二份报告/);
  assert.equal(pd.querySelector("#stop").hidden, false);
  assert.equal(pd.querySelectorAll(".history-row.unread").length, 1);
  pd.querySelector("#stop").click();
  await firstRun;
  assert.equal(pending[0].signal.aborted, true);
  assert.equal(pending[1].signal.aborted, false);
  [...pd.querySelectorAll(".history-open")]
    .find((b) => b.title === "第二个分析")
    .click();
  assert.match(pd.querySelector("#messages").textContent, /第二份报告/);
  assert.equal(pd.querySelectorAll(".history-row.unread").length, 0);
  pd.querySelector("#new-chat").click();
  const deletedRun = parallel.send("删除运行中的对话");
  pd.querySelector(".history-row.selected .history-delete").click();
  assert.equal(pd.querySelector("#confirm-delete").textContent, "停止并删除");
  pd.querySelector("#cancel-delete").click();
  assert.equal(pending[2].signal.aborted, false);
  pd.querySelector(".history-row.selected .history-delete").click();
  pd.querySelector("#confirm-delete").click();
  await deletedRun;
  assert.equal(pending[2].signal.aborted, true);
  const savedChats = JSON.parse(
    parallel.localStorage.getItem("qiqi.conversations.v1.admin"),
  ).chats;
  assert.equal(savedChats.length, 2);
  assert.equal(
    savedChats.some((c) => c.title === "删除运行中的对话"),
    false,
  );
  parallel.close();
  console.log(
    "PASS concurrent conversations, background completion, independent stop, unread state and deletion during generation",
  );
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
