package dev.qiqi.dataagent.observability;

import dev.qiqi.dataagent.web.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded diagnostics only: no prompts, SQL, arguments, result rows or exception bodies. */
public final class RunTrace {
    private static final Logger log = LoggerFactory.getLogger(RunTrace.class);
    private static final java.util.Set<String> WEB_CODES = java.util.Set.of("WEB_AUTH", "WEB_DENIED", "WEB_INVALID_INPUT", "WEB_TIMEOUT", "WEB_RATE_LIMIT", "WEB_UNAVAILABLE");
    private final String id = UUID.randomUUID().toString();
    private final long owner;
    private final String conversationId;
    private final boolean online;
    private final Instant startedAt = Instant.now();
    private final long startNanos = System.nanoTime();
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private String status = "RUNNING";
    private String errorCode;
    private Long durationMs;
    private boolean agentEnded;
    private final ExecutionJournal execution;
    private boolean hasUsage;
    private long inputTokens, outputTokens, cachedTokens;

    RunTrace(long owner, String conversationId, boolean online) {
        this.owner = owner; this.conversationId = conversationId; this.online = online;
        this.execution = new ExecutionJournal(id, conversationId);
    }
    public String id() { return id; }
    public ExecutionJournal execution() { return execution; }
    public long owner() { return owner; }
    public Instant startedAt() { return startedAt; }
    public synchronized boolean running() { return status.equals("RUNNING"); }

    public synchronized void accept(StreamEvent event) {
        if (!running()) return;
        execution.accept(event);
        if (event.type().equals("ERROR")) { fail("AGENT_FAILED"); return; }
        Map<String, Object> data = event.data();
        String type = event.type();
        if (type.equals("AGENT_END")) agentEnded = true;
        if (type.equals("EXCEED_MAX_ITERS")) errorCode = "MAX_ITERATIONS";
        if (type.equals("REQUEST_STOP")) errorCode = "STOP_REQUESTED";
        if (type.equals("ALL_TOOLS_DENIED")) errorCode = "TOOLS_DENIED";
        if (type.equals("REQUIRE_USER_CONFIRM") || type.equals("REQUIRE_EXTERNAL_EXECUTION")) errorCode = "AWAITING_ACTION";
        boolean model = type.startsWith("MODEL_CALL_");
        boolean tool = type.equals("TOOL_CALL_START") || type.equals("TOOL_RESULT_START") || type.equals("TOOL_RESULT_END");
        if (!model && !tool) return;
        String callId = safe(data.get(model ? "replyId" : "toolCallId"));
        String key = (model ? "model:" : "tool:") + callId;
        if (!operations.containsKey(key) && operations.size() >= 250) return;
        Operation op = operations.computeIfAbsent(key, ignored -> new Operation(callId, model ? "model" : "tool",
                model ? "model" : safe(data.get("toolCallName"))));
        if (op.durationMs != null) return; // duplicated end events must not double-count usage
        if (type.equals("TOOL_RESULT_START") || type.equals("MODEL_CALL_START")) {
            op.started = System.nanoTime(); op.status = "RUNNING";
        }
        if (type.equals("TOOL_RESULT_END") || type.equals("MODEL_CALL_END")) {
            String resultState = String.valueOf(data.getOrDefault("state", "success"));
            op.status = resultState.equalsIgnoreCase("success") ? "SUCCEEDED" : "FAILED";
            if (op.status.equals("FAILED")) {
                op.errorCode = "TOOL_FAILED";
                if (data.get("metadata") instanceof Map<?, ?> metadata && metadata.get("failureCode") instanceof String code && WEB_CODES.contains(code))
                    op.errorCode = code;
            }
            op.durationMs = elapsed(op.started);
            if (model && data.get("usage") instanceof Map<?, ?> usage) {
                hasUsage = true;
                inputTokens += tokens(usage.get("inputTokens")); outputTokens += tokens(usage.get("outputTokens"));
                cachedTokens += tokens(usage.get("cachedTokens"));
            }
        }
    }
    public synchronized void fail(String code) { if (running()) { errorCode = code; finish("FAILED"); } }
    public synchronized void cancel() { cancel("CLIENT_DISCONNECTED"); }
    public synchronized void cancel(String code) { if (running()) { errorCode = code; finish("CANCELLED"); } }
    public synchronized void complete() {
        if (!running()) return;
        if (errorCode != null) finish(errorCode.equals("STOP_REQUESTED") ? "CANCELLED" : "INCOMPLETE");
        else if (!agentEnded || operations.values().stream().anyMatch(op -> op.durationMs == null)) {
            errorCode = "STREAM_INCOMPLETE"; finish("INCOMPLETE");
        } else {
            execution.completeInProgressIfReported();
            if (execution.hasUnfinishedPlan()) {
                errorCode = "PLAN_INCOMPLETE"; finish("INCOMPLETE");
            } else finish(operations.values().stream().anyMatch(op -> op.status.equals("FAILED")) ? "PARTIAL" : "SUCCEEDED");
        }
    }
    private void finish(String terminal) {
        if (!running()) return;
        status = terminal; durationMs = elapsed(startNanos);
        execution.finish(terminal, errorCode);
        operations.values().stream().filter(op -> op.durationMs == null).forEach(op -> {
            op.status = terminal.equals("CANCELLED") ? "CANCELLED" : "INCOMPLETE";
            op.errorCode = errorCode; op.durationMs = elapsed(op.started);
        });
        log.info("qiqi_run runId={} userId={} status={} durationMs={} operations={} errorCode={}",
                id, owner, status, durationMs, operations.size(), errorCode);
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(id, conversationId, online, status, startedAt.toString(),
                durationMs == null ? elapsed(startNanos) : durationMs, errorCode,
                hasUsage ? new Usage(inputTokens, outputTokens, cachedTokens) : null,
                operations.values().stream().map(op -> new OperationView(op.id, op.kind, op.name, op.status,
                        op.durationMs == null ? elapsed(op.started) : op.durationMs, op.errorCode)).toList());
    }
    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000); }
    private static long tokens(Object value) { return value instanceof Number n ? Math.max(0, n.longValue()) : 0; }
    private static String safe(Object raw) {
        String value = String.valueOf(raw);
        return value.matches("[a-zA-Z0-9_.:-]{1,100}") ? value : "unknown";
    }
    private static final class Operation {
        final String id, kind, name;
        long started = System.nanoTime();
        String status = "QUEUED", errorCode;
        Long durationMs;
        Operation(String id, String kind, String name) { this.id = id; this.kind = kind; this.name = name; }
    }
    public record Usage(long inputTokens, long outputTokens, long cachedTokens) {}
    public record OperationView(String id, String kind, String name, String status, long durationMs, String errorCode) {}
    public record Snapshot(String runId, String conversationId, boolean online, String status, String startedAt,
                           long durationMs, String errorCode, Usage usage, List<OperationView> operations) {}
}
