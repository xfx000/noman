# Qiqi Results-First Analysis Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the tool-heavy timeline with a results-first Chinese analysis experience and delay chart generation/display until evidence has been verified and the run reaches its final presentation phase.

**Architecture:** `ExecutionJournal` remains the single durable projection, but publishes only user-facing textBlock narration and marks the final reply explicitly; raw thinking never becomes replayable UI content. The browser uses a two-state presentation model: while running it shows progress, the latest Chinese business update and a collapsed execution summary; at terminal state it promotes the final report and reveals staged chart artifacts. Chart generation still consumes structured `queryId` evidence, while prompt changes move the tool call from “after every aggregate query” to the final chart/report plan item.

**Tech Stack:** Java 21, Spring Boot 3.5, AgentScope Java 2.0.3, Reactor, Jackson, vanilla JavaScript, CSS, Node.js/JSDOM regression tests.

## Global Constraints

- Do not expose complete chain-of-thought or provider reasoning content.
- User-visible updates and reports must be Simplified Chinese for Chinese questions.
- Charts must use validated structured query evidence, never numbers parsed from prose.
- No chart, broken-chart placeholder, or “chart ready” message is visible while the run is active.
- Keep the existing SQL authorization, chart MCP contract, SSE envelope, Markdown sanitizer, history persistence and report export behavior.
- Add no frontend runtime dependency or framework.

---

## File Structure

- `src/main/java/dev/qiqi/dataagent/observability/ExecutionJournal.java` — durable separation between private thinking, process narration and the final reply.
- `src/main/java/dev/qiqi/dataagent/agent/DataAgentFactory.java` — user-visible language and late chart-generation contract.
- `src/main/resources/skills/data-analysis/SKILL.md` — the same language and chart-phase contract supplied by the loaded skill.
- `src/main/resources/static/app.js` — running/final presentation state, staged charts and compact execution details.
- `src/main/resources/static/styles.css` — results-first card hierarchy and responsive visual treatment.
- `src/test/java/dev/qiqi/dataagent/observability/ExecutionJournalTest.java` — projection semantics.
- `src/test/java/dev/qiqi/dataagent/agent/DataAgentPromptTest.java` — prompt contract.
- `src/test/java/dev/qiqi/dataagent/web/ChatProjectionTest.java` — sanitized journal projection at the SSE boundary.
- `src/test/frontend/verify.cjs` — chart timing, narrative hierarchy, compact tools, restore and safety regressions.
- `README.md` — observable UX and chart timing.

---

### Task 1: Separate private thinking, process narration and final report

**Files:**
- Modify: `src/test/java/dev/qiqi/dataagent/observability/ExecutionJournalTest.java`
- Modify: `src/test/java/dev/qiqi/dataagent/web/ChatProjectionTest.java`
- Modify: `src/main/java/dev/qiqi/dataagent/observability/ExecutionJournal.java`

**Interfaces:**
- Consumes: `StreamEvent(type, data)` from `AgentEventMapper`.
- Produces: `ExecutionJournal.Snapshot.finalNarrationId(): String`; `narrations()` contains only sanitized `kind="text"` user-facing updates; `text()` contains the latest reply.

- [ ] **Step 1: Replace the existing thinking exposure test with the final-reply contract**

```java
@Test void privateThinkingIsNotPublishedAndAgentEndMarksTheFinalTextReply() {
    var journal = new ExecutionJournal("run", "session");
    journal.accept(new StreamEvent("THINKING_BLOCK_DELTA",
            Map.of("replyId", "private", "delta", "token=secret-value internal reasoning")));
    journal.accept(new StreamEvent("TEXT_BLOCK_DELTA",
            Map.of("replyId", "progress", "delta", "正在核对已付款订单口径。")));
    journal.accept(event("TOOL_CALL_START", "sql", "execute_sql", ""));
    journal.accept(new StreamEvent("TEXT_BLOCK_DELTA",
            Map.of("replyId", "final", "delta", "2 月收入较 1 月增长。")));
    journal.accept(new StreamEvent("AGENT_END", Map.of("replyId", "final")));

    var snapshot = journal.snapshot();
    assertThat(snapshot.toString()).doesNotContain("internal reasoning", "secret-value");
    assertThat(snapshot.narrations()).extracting(ExecutionJournal.Narration::id)
            .containsExactly("text:progress", "text:final");
    assertThat(snapshot.finalNarrationId()).isEqualTo("text:final");
    assertThat(snapshot.text()).isEqualTo("2 月收入较 1 月增长。");
    assertThat(snapshot.steps()).extracting(ExecutionJournal.Step::kind)
            .containsExactly("text", "tool", "text");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
mvn -q -Dtest=ExecutionJournalTest#privateThinkingIsNotPublishedAndAgentEndMarksTheFinalTextReply test
```

Expected: FAIL because thinking is currently persisted and `finalNarrationId()` does not exist.

- [ ] **Step 3: Implement final-reply classification**

In `ExecutionJournal`:

```java
private String status = "RUNNING", errorCode, progress = "正在准备分析",
        text = "", replyId, finalNarrationId;

if (type.equals("THINKING_BLOCK_DELTA")) {
    progress = "正在分析问题与证据";
    revision++;
    return;
}
if (type.equals("TEXT_BLOCK_DELTA")) {
    String nextReply = replyKey(data);
    if (replyId != null && !replyId.equals(nextReply)) text = "";
    replyId = nextReply;
    text = append(text, string(data.get("delta")), TEXT_LIMIT);
    appendNarration("text:" + nextReply, "text", string(data.get("delta")));
    progress = "正在整理分析进展";
    revision++;
    return;
}
if (type.equals("AGENT_END")) {
    finalNarrationId = replyId == null ? null : "text:" + replyId;
    revision++;
    return;
}
```

Add `String finalNarrationId` to the end of `Snapshot`, defaulting naturally to
`null` for old persisted JSON, and pass it through `snapshot()` and
`interrupted(...)`.

- [ ] **Step 4: Update the controller projection assertion**

Keep raw `THINKING_BLOCK_DELTA` absent from SSE output and assert the normalized
execution snapshot contains no private reasoning:

```java
assertThat(events).extracting(StreamEvent::type)
        .doesNotContain("THINKING_BLOCK_DELTA");
assertThat(execution.toString())
        .doesNotContain("private-credential", "private-reasoning");
assertThat(execution.narrations())
        .extracting(ExecutionJournal.Narration::kind)
        .containsOnly("text");
```

- [ ] **Step 5: Run projection tests and verify GREEN**

Run:

```powershell
mvn -q -Dtest=ExecutionJournalTest,ChatProjectionTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/dev/qiqi/dataagent/observability/ExecutionJournal.java `
  src/test/java/dev/qiqi/dataagent/observability/ExecutionJournalTest.java `
  src/test/java/dev/qiqi/dataagent/web/ChatProjectionTest.java
git commit -m "refactor(observability): 区分过程说明和最终报告"
```

---

### Task 2: Enforce Chinese business updates and late chart selection

**Files:**
- Modify: `src/test/java/dev/qiqi/dataagent/agent/DataAgentPromptTest.java`
- Modify: `src/main/java/dev/qiqi/dataagent/agent/DataAgentFactory.java`
- Modify: `src/main/resources/skills/data-analysis/SKILL.md`
- Modify: `src/main/java/dev/qiqi/dataagent/chart/GenerateChartTool.java`

**Interfaces:**
- Consumes: the existing AgentScope system prompt, loaded `data-analysis` Skill and `generate_chart` schema.
- Produces: one or two Chinese user-facing business-update sentences on intermediate tool turns, followed by chart generation only in the verified presentation phase.

- [ ] **Step 1: Write the prompt contract test**

```java
@Test void visibleUpdatesAreChineseBusinessNarrationAndChartsWaitForVerifiedEvidence() {
    assertThat(DataAgentFactory.BASE_PROMPT)
            .contains("Simplified Chinese")
            .contains("user-facing business update")
            .contains("after the analysis evidence has been verified")
            .contains("selected queryId")
            .doesNotContain("brief reasoning")
            .doesNotContain("call generate_chart immediately");
}
```

- [ ] **Step 2: Run the prompt test and verify RED**

Run:

```powershell
mvn -q -Dtest=DataAgentPromptTest test
```

Expected: FAIL on the new language and late-chart phrases.

- [ ] **Step 3: Replace the intermediate-output and chart rules**

Use this prompt contract in `DataAgentFactory.BASE_PROMPT`:

```text
For a Chinese user request, every user-facing business update and the final
report must be in Simplified Chinese. Before a tool call, you may emit one or
two concise user-facing business update sentences: state the metric, scope or
verified stage finding, without revealing hidden chain-of-thought.

When generate_chart is available, wait until the analysis evidence has been
verified and the chart/report task is in progress. Select the queryId that
supports the final finding, then call generate_chart with its actual category
and value columns. Do not generate a chart immediately after every aggregate
query.
```

Update `SKILL.md` with the same Chinese rules: visible updates are concise
business descriptions, all Chinese for a Chinese request, and chart generation
belongs to the final “图表与报告” task after evidence verification.

Update `GenerateChartTool.getDescription()` from “Call immediately after...” to:

```java
return "Generate a final bar, line or pie chart from a verified execute_sql "
        + "queryId selected after analysis evidence is complete. ...";
```

- [ ] **Step 4: Run prompt and toolkit tests and verify GREEN**

Run:

```powershell
mvn -q -Dtest=DataAgentPromptTest,DataAgentToolkitTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/dev/qiqi/dataagent/agent/DataAgentFactory.java `
  src/main/java/dev/qiqi/dataagent/chart/GenerateChartTool.java `
  src/main/resources/skills/data-analysis/SKILL.md `
  src/test/java/dev/qiqi/dataagent/agent/DataAgentPromptTest.java
git commit -m "fix(agent): 延后图表生成并统一中文叙述"
```

---

### Task 3: Stage charts until terminal state

**Files:**
- Modify: `src/test/frontend/verify.cjs`
- Modify: `src/main/resources/static/app.js`

**Interfaces:**
- Produces: `stageChart(response, chart)`, `recordChartError(response, message)`,
  `revealCharts(response)` and `response.renderedCharts: Set<string>`.
- Consumes: chart metadata from raw `TOOL_RESULT_END`, normalized
  `EXECUTION_UPDATE`, history restore, `AGENT_END` and terminal `RUN_END`.

- [ ] **Step 1: Add a controlled-stream regression**

Add a helper to `verify.cjs`:

```javascript
function controlledStream() {
  let controller;
  const response = new Response(new ReadableStream({
    start(value) { controller = value; },
  }));
  return {
    response,
    emit(event) {
      controller.enqueue(new TextEncoder().encode(
        `event:${event.type}\r\ndata:${JSON.stringify(event)}\r\n\r\n`,
      ));
    },
    close() { controller.close(); },
  };
}
```

Add a test that emits a successful chart tool result before terminal events:

```javascript
const stream = controlledStream();
const staged = await setup(() => stream.response);
const pending = staged.send("比较收入");
stream.emit(ev("TOOL_RESULT_END", {
  toolCallId: "chart", toolCallName: "generate_chart", state: "success",
  metadata: { chart },
}));
await new Promise(resolve => setTimeout(resolve, 10));
assert.equal(staged.document.querySelectorAll(".chart-card").length, 0);
stream.emit(ev("TEXT_BLOCK_DELTA", { replyId: "final", delta: "分析完成。" }));
stream.emit(ev("AGENT_END"));
stream.emit(ev("RUN_END", { run: { ...sampleRun, status: "SUCCEEDED" } }));
stream.close();
await pending;
assert.equal(staged.document.querySelectorAll(".chart-card").length, 1);
```

- [ ] **Step 2: Run the frontend test and verify RED**

Run:

```powershell
npm --prefix src/test/frontend test
```

Expected: FAIL because `addChart` renders immediately.

- [ ] **Step 3: Split chart caching from rendering**

Implement:

```javascript
function stageChart(response, chart) {
  const source = chartSource(chart?.source);
  if (!source || typeof chart.id !== "string"
      || typeof chart.queryId !== "string"
      || typeof chart.title !== "string") return;
  response.charts.set(chart.id, {
    id: chart.id, queryId: chart.queryId, title: chart.title,
    type: chart.type, source,
  });
}

function revealCharts(response) {
  if (!response.agentEnded && !response.runEnded) return;
  for (const chart of response.charts.values()) renderChart(response, chart);
}
```

Move the existing image/figure DOM code from `addChart` to `renderChart`, guarded
by `response.renderedCharts`. Change all live, execution and restore paths to
call `stageChart`; terminal `AGENT_END`, terminal `RUN_END`, and completed
history restore call `revealCharts`. Keep chart failures in
`.execution-details` as technical warnings; never place a broken-chart card or
chart error placeholder in `.final-result`.

- [ ] **Step 4: Run the frontend test and verify GREEN**

Run:

```powershell
npm --prefix src/test/frontend test
```

Expected: PASS, including duplicate-event, unsafe-source, history and outage tests.

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/static/app.js src/test/frontend/verify.cjs
git commit -m "fix(ui): 在分析终态统一展示图表"
```

---

### Task 4: Replace the tool timeline with the results-first card

**Files:**
- Modify: `src/test/frontend/verify.cjs`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/styles.css`

**Interfaces:**
- Produces: `syncPresentation(response)`, `applyNarratives(response, execution)`
  and `executionCounts(tools)`.
- Reuses: existing sanitized Markdown renderer, `toolDetails`, todo snapshots,
  evidence/chart/source stores and history capture.

- [ ] **Step 1: Add layout behavior assertions**

Update the normalized journal fixture to contain two visible text narrations and
`finalNarrationId: "text:final"`. Assert:

```javascript
assert.match(document.querySelector(".analysis-narrative").textContent,
  /正在核对已付款订单口径/);
assert.equal(document.querySelector(".execution-details").open, true);
assert.match(document.querySelector(".execution-summary").textContent,
  /2 次执行.*1 项未成功/);
assert.equal(document.querySelectorAll(".timeline-item").length, 0);
assert.match(document.querySelector(".final-result").textContent,
  /已有真实查询证据/);
assert.deepEqual(
  [...document.querySelector(".final-result").children].map(node => node.className),
  ["result-conclusion", "chart-results", "report markdown-body",
   "web-sources", "query-evidence"],
);
```

Use a second all-success fixture and assert `.execution-details.open === false`.
Assert private thinking fixture text never appears.

- [ ] **Step 2: Run the frontend test and verify RED**

Run:

```powershell
npm --prefix src/test/frontend test
```

Expected: FAIL because the current DOM is a flat `.timeline`.

- [ ] **Step 3: Build the two-state DOM in `createResponse`**

Replace the timeline as the primary container with:

```javascript
const analysisProgress = element("section", "analysis-progress");
const progressTitle = element("strong", "analysis-stage", "正在理解你的问题");
const progressCount = element("span", "analysis-count", "准备中");
analysisProgress.append(progressTitle, progressCount);

const analysisNarrative = element("section", "analysis-narrative");
const narrativeLabel = element("div", "analysis-label", "当前分析思路");
const narrativeBody = element("div", "analysis-narrative-body");
const stageFindings = element("div", "stage-findings");
analysisNarrative.append(narrativeLabel, narrativeBody, stageFindings);

const executionDetails = element("details", "execution-details");
const executionSummary = element("summary", "execution-summary", "技术执行记录");
const executionList = element("div", "execution-list");
executionDetails.append(executionSummary, executionList);

const finalResult = element("section", "final-result");
finalResult.hidden = true;
finalResult.append(report, chartArea, sourceArea, evidence);
```

Append `analysisProgress`, `analysisNarrative`, `finalResult`,
`executionDetails`, notices, diagnostics and actions in that order.

- [ ] **Step 4: Render narrative and compact execution states**

Implement:

```javascript
function executionCounts(tools) {
  return tools.reduce((counts, tool) => {
    counts.total++;
    if (tool.status === "FAILED") counts.failed++;
    else if (["RUNNING", "QUEUED"].includes(tool.status)) counts.running++;
    else if (tool.status === "SUCCEEDED") counts.succeeded++;
    return counts;
  }, { total: 0, failed: 0, running: 0, succeeded: 0 });
}
```

`applyNarratives` filters out `execution.finalNarrationId`, ignores non-text
narrations, renders the latest process text in `.analysis-narrative-body`, and
renders up to three earlier messages in `.stage-findings`.

`applyExecution` renders non-`todoWrite` tools inside `.execution-list`. It
keeps the outer `.execution-details` closed unless at least one tool failed,
and updates its summary to:

```text
技术执行记录 · 8 次执行 · 1 项进行中 · 1 项未成功
```

Update the progress header with completed/total todos and the current todo
content. On terminal state, show `.final-result`; hide the running narrative
when a final report exists, but retain the process inside the collapsed
execution/details section. Only promote text when
`execution.finalNarrationId` matches the final text narration. A failed,
cancelled or incomplete run without that marker keeps its verified process
narrative and evidence but does not display `.final-result`. For backward
compatibility, a completed history turn saved before this field existed may
use its persisted non-empty `turn.text` as the final report.

- [ ] **Step 5: Apply the approved visual hierarchy**

Add CSS for:

```css
.analysis-progress { /* flex header, muted green stage pill and 4px progress */ }
.analysis-narrative { /* pale surface, 3px green left accent, 12px radius */ }
.stage-findings { /* compact verified-update list */ }
.execution-details { /* single subdued bordered disclosure */ }
.execution-list { /* compact rows, no vertical timeline rail */ }
.final-result { /* stronger top spacing and content hierarchy */ }
.message.assistant.is-complete { /* slightly stronger result card shadow */ }
```

Keep dark/theme variables and the existing `max-width: 600px` behavior. Remove
or leave unused the old `.timeline-*` styles only after tests no longer depend
on them.

- [ ] **Step 6: Run frontend tests and verify GREEN**

Run:

```powershell
npm --prefix src/test/frontend test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/resources/static/app.js `
  src/main/resources/static/styles.css `
  src/test/frontend/verify.cjs
git commit -m "feat(ui): 改造结果优先分析卡片"
```

---

### Task 5: Documentation and full regression

**Files:**
- Modify: `README.md`
- Verify: all files changed in Tasks 1–4

**Interfaces:**
- No new interface; documents the externally visible behavior.

- [ ] **Step 1: Document the user-visible contract**

Update README capability bullets to state:

```text
- 结果优先分析卡片：运行中展示中文业务说明和折叠执行摘要；完成后展示结论、图表和报告。
- 图表在证据核对完成后从最终选定的 queryId 生成，运行终态前只缓存、不展示。
```

- [ ] **Step 2: Run Java regressions**

Run:

```powershell
mvn -q -Dtest='!LocalSettingsTest' test
```

Expected: exit code 0. `LocalSettingsTest` remains excluded on Windows because
its POSIX permission assertion is unrelated to this UX change.

- [ ] **Step 3: Run frontend regressions**

Run:

```powershell
npm --prefix src/test/frontend test
```

Expected: every `PASS` line completes and exit code is 0.

- [ ] **Step 4: Check the diff**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only intended source, test, README and existing
approved work remain modified. `.superpowers/` is not staged.

- [ ] **Step 5: Commit**

```powershell
git add README.md
git commit -m "docs(readme): 说明结果优先分析与图表时序"
```
