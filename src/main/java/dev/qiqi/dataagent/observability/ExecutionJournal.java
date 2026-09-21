package dev.qiqi.dataagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qiqi.dataagent.web.StreamEvent;
import java.util.*;

/** Framework events are projected once for both live rendering and durable replay. */
public final class ExecutionJournal {
    private static final int MAX_TOOLS = 128, BUFFER_LIMIT = 65536, TEXT_LIMIT = 200000;
    private final String runId, conversationId;
    private final Map<String, Call> calls = new LinkedHashMap<>();
    private final Map<String, JsonNode> evidence = new LinkedHashMap<>(), charts = new LinkedHashMap<>(), sources = new LinkedHashMap<>();
    private List<JsonNode> todos = List.of();
    private final Map<String, NarrationBuf> narrations = new LinkedHashMap<>();
    private final List<Step> steps = new ArrayList<>();
    private String status = "RUNNING", errorCode, progress = "正在准备分析", text = "", replyId, finalNarrationId;
    private long revision;
    private boolean truncated;

    public ExecutionJournal(String runId, String conversationId) { this.runId = runId; this.conversationId = conversationId; }

    public synchronized void accept(StreamEvent event) {
        if (!status.equals("RUNNING")) return;
        var data = event.data(); String type = event.type();
        if (type.equals("THINKING_BLOCK_DELTA")) {
            progress = "正在分析问题与证据";
            revision++; return;
        }
        if (type.equals("TEXT_BLOCK_DELTA")) {
            String nextReply = replyKey(data);
            if (replyId != null && !replyId.equals(nextReply)) text = "";
            replyId = nextReply;
            text = append(text, string(data.get("delta")), TEXT_LIMIT);
            appendNarration("text:" + nextReply, "text", string(data.get("delta")));
            progress = "正在整理分析进展";
            revision++; return;
        }
        if (type.equals("AGENT_END")) {
            finalNarrationId = replyId == null ? null : "text:" + replyId;
            revision++; return;
        }
        if (type.equals("MODEL_CALL_START")) { progress = "正在整理问题与已有证据"; revision++; return; }
        if (type.equals("EXCEED_MAX_ITERS")) { progress = "已达到分析轮数上限，保留已完成的证据"; revision++; return; }
        if (!type.startsWith("TOOL_CALL_") && !type.startsWith("TOOL_RESULT_")) return;
        String id = string(data.get("toolCallId"));
        // Missing IDs must never merge unrelated parallel calls into an "undefined" item.
        if (id.isBlank() || id.length() > 200) return;
        if (!calls.containsKey(id) && calls.size() >= MAX_TOOLS) { truncated = true; return; }
        boolean first = !calls.containsKey(id);
        Call call = calls.computeIfAbsent(id, Call::new);
        if (first) steps.add(new Step("tool", id));
        if (call.ended) return;
        String name = string(data.get("toolCallName"));
        if (!name.isBlank()) call.name = DisplaySanitizer.limit(name, 100);
        switch (type) {
            case "TOOL_CALL_DELTA" -> call.rawArguments = append(call.rawArguments, string(data.get("delta")), BUFFER_LIMIT);
            case "TOOL_CALL_END" -> call.arguments = DisplaySanitizer.structured(call.rawArguments, false);
            case "TOOL_RESULT_START" -> {
                call.arguments = DisplaySanitizer.structured(call.rawArguments, false);
                if (!call.executing) { call.started = System.nanoTime(); call.executing = true; }
                call.status = "RUNNING"; progress = label(call.name) + "…";
            }
            case "TOOL_RESULT_TEXT_DELTA" -> call.rawResult = append(call.rawResult, string(data.get("delta")), BUFFER_LIMIT);
            case "TOOL_RESULT_DATA_DELTA" -> call.dataBlocks++;
            case "TOOL_RESULT_END" -> {
                call.arguments = DisplaySanitizer.structured(call.rawArguments, false);
                call.result = DisplaySanitizer.structured(call.rawResult, true);
                if (call.dataBlocks > 0) call.result += "\n[另返回 " + call.dataBlocks + " 个数据块]";
                call.status = "success".equalsIgnoreCase(string(data.get("state"))) ? "SUCCEEDED" : "FAILED";
                call.durationMs = elapsed(call.started); call.ended = true;
                if (data.get("metadata") instanceof Map<?, ?> meta) {
                    if (call.status.equals("SUCCEEDED")) {
                        if (call.name.equals("todoWrite") && meta.get("todos") instanceof List<?> plan)
                            todos = plan.stream().limit(20).map(DisplaySanitizer::clean).toList();
                        remember(evidence, meta.get("evidence"), "queryId");
                        remember(charts, meta.get("chart"), "id");
                        if (meta.get("webSearch") instanceof Map<?, ?> web && web.get("sources") instanceof List<?> items)
                            items.stream().limit(10).forEach(item -> remember(sources, item, "url"));
                    }
                }
                if (call.status.equals("SUCCEEDED") && Set.of("execute_sql", "analyze_file").contains(call.name)) {
                    try { remember(evidence, DisplaySanitizer.JSON.readTree(call.rawResult), "queryId"); } catch (Exception ignored) { }
                }
                progress = label(call.name) + (call.status.equals("SUCCEEDED") ? "已完成" : "未成功，请查看详情");
                // Discard raw input/result as soon as the sanitized display projection is complete.
                call.rawArguments = ""; call.rawResult = "";
            }
            default -> { }
        }
        revision++;
    }
    private void remember(Map<String, JsonNode> target, Object item, String key) {
        if (item == null || target.size() >= MAX_TOOLS) return;
        JsonNode safe = DisplaySanitizer.clean(item);
        if (key.equals("queryId") && safe.isObject())
            ((com.fasterxml.jackson.databind.node.ObjectNode) safe).put("displayRedacted", true);
        if (safe.path(key).isTextual() && !safe.path(key).asText().isBlank()) target.put(safe.path(key).asText(), safe);
    }
    public synchronized void finish(String terminal, String code) {
        if (!status.equals("RUNNING")) return;
        status = terminal; errorCode = code;
        for (Call call : calls.values()) if (!call.ended) {
            call.arguments = DisplaySanitizer.structured(call.rawArguments, false);
            call.result = "执行中断，未收到完整结果";
            call.status = terminal.equals("CANCELLED") ? "CANCELLED" : "INCOMPLETE";
            call.ended = true; call.durationMs = elapsed(call.started); call.rawArguments = ""; call.rawResult = "";
        }
        progress = code != null && code.equals("MAX_ITERATIONS") ? "达到分析轮数上限，可继续完成剩余计划"
                : terminal.equals("SUCCEEDED") ? "分析完成" : terminal.equals("PARTIAL") ? "分析结束，部分工具未成功"
                : terminal.equals("CANCELLED") ? "分析已停止，已保留进度" : "分析未完成，已保留证据与计划";
        revision++;
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(1, runId, conversationId, revision, status, errorCode, progress,
                DisplaySanitizer.text(text), calls.values().stream().map(c -> new ToolView(c.id, c.name, c.status,
                        c.arguments, c.result, c.ended ? c.durationMs : elapsed(c.started))).toList(),
                todos, List.copyOf(evidence.values()), List.copyOf(charts.values()), List.copyOf(sources.values()), truncated,
                narrations.values().stream().map(n -> new Narration(n.id, n.kind, DisplaySanitizer.text(n.content))).toList(),
                List.copyOf(steps), finalNarrationId);
    }
    public synchronized boolean hasUnfinishedPlan() {
        return todos.stream().anyMatch(todo -> !todo.path("status").asText().equals("completed"));
    }
    public static Snapshot interrupted(Snapshot saved) {
        return new Snapshot(saved.version, saved.runId, saved.conversationId, saved.revision + 1, "INCOMPLETE", "SERVER_RESTART",
                "服务重启，已恢复保存的计划与证据", saved.text,
                saved.tools.stream().map(t -> Set.of("QUEUED", "RUNNING").contains(t.status)
                        ? new ToolView(t.id, t.name, "INCOMPLETE", t.arguments, "服务重启，未收到完整结果", t.durationMs) : t).toList(),
                saved.todos, saved.evidence, saved.charts, saved.sources, saved.truncated, saved.narrations, saved.steps,
                saved.finalNarrationId);
    }
    private void appendNarration(String id, String kind, String delta) {
        if (id.length() > 220) return;
        NarrationBuf buf = narrations.get(id);
        if (buf == null) {
            if (narrations.size() >= MAX_TOOLS) { truncated = true; return; }
            buf = new NarrationBuf(id, kind);
            narrations.put(id, buf);
            steps.add(new Step(kind, id));
        }
        buf.content = append(buf.content, delta, TEXT_LIMIT);
    }
    private static String replyKey(Map<String, Object> data) {
        String reply = string(data.get("replyId"));
        return reply.isBlank() ? "default" : reply;
    }
    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static String append(String current, String delta, int max) {
        return current.length() >= max ? current : current + delta.substring(0, Math.min(delta.length(), max - current.length()));
    }
    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1000000); }
    private static String label(String name) {
        return switch (name) {
            case "todoWrite" -> "更新分析计划";
            case "load_skill_through_path" -> "加载分析规范";
            case "list_tables" -> "查看业务数据表";
            case "describe_table" -> "读取表结构";
            case "validate_sql" -> "校验 SQL";
            case "execute_sql" -> "执行只读查询";
            case "current_time" -> "确认当前时间";
            case "generate_chart" -> "生成数据图表";
            case "analyze_file" -> "分析上传文件";
            case "web_search" -> "搜索公开资料";
            case "reset_equipped_tools" -> "发现并加载工具";
            default -> "执行工具 " + name;
        };
    }
    private static final class Call {
        final String id;
        long started = System.nanoTime();
        String name = "unknown", status = "QUEUED", rawArguments = "", rawResult = "", arguments = "", result = "";
        long durationMs;
        boolean ended, executing;
        int dataBlocks;
        Call(String id) { this.id = id; }
    }
    private static final class NarrationBuf {
        final String id, kind;
        String content = "";
        NarrationBuf(String id, String kind) { this.id = id; this.kind = kind; }
    }
    public record ToolView(String id, String name, String status, String arguments, String result, long durationMs) { }
    public record Narration(String id, String kind, String content) { }
    public record Step(String kind, String id) { }
    public record Snapshot(int version, String runId, String conversationId, long revision, String status, String errorCode,
                           String progress, String text, List<ToolView> tools, List<JsonNode> todos,
                           List<JsonNode> evidence, List<JsonNode> charts, List<JsonNode> sources, boolean truncated,
                           List<Narration> narrations, List<Step> steps, String finalNarrationId) {
        public Snapshot {
            narrations = narrations == null ? List.of() : List.copyOf(narrations);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
