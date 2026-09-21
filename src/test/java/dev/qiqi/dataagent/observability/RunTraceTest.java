package dev.qiqi.dataagent.observability;

import dev.qiqi.dataagent.web.StreamEvent;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RunTraceTest {
    @Test void countsUsageOnceAndRetainsToolFailureDespiteAgentCompletion() {
        RunTrace trace = new RunTrace(1, "s", true);
        var usage = new StreamEvent("MODEL_CALL_END", Map.of("replyId", "r", "usage", Map.of("inputTokens", 30, "outputTokens", 8, "cachedTokens", 4)));
        trace.accept(new StreamEvent("MODEL_CALL_START", Map.of("replyId", "r")));
        trace.accept(usage); trace.accept(usage);
        trace.accept(new StreamEvent("TOOL_RESULT_START", Map.of("toolCallId", "t", "toolCallName", "web_search")));
        trace.accept(new StreamEvent("TOOL_RESULT_TEXT_DELTA", Map.of("toolCallId", "t", "delta", "private-output")));
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "t", "state", "error", "metadata", Map.of("failureCode", "WEB_TIMEOUT"))));
        trace.accept(new StreamEvent("AGENT_END", Map.of())); trace.complete();
        var snapshot = trace.snapshot();
        assertThat(snapshot.status()).isEqualTo("PARTIAL");
        assertThat(snapshot.usage().inputTokens()).isEqualTo(30);
        assertThat(snapshot.operations()).hasSize(2);
        assertThat(snapshot.operations().getLast().errorCode()).isEqualTo("WEB_TIMEOUT");
        assertThat(snapshot.toString()).doesNotContain("private-output");
    }
    @Test void cancellationAndIncompleteStreamsAreNotSuccess() {
        RunTrace cancelled = new RunTrace(1, "s", false);
        cancelled.accept(new StreamEvent("MODEL_CALL_START", Map.of("replyId", "r")));
        cancelled.cancel(); cancelled.complete();
        assertThat(cancelled.snapshot().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.snapshot().operations().getFirst().status()).isEqualTo("CANCELLED");
        RunTrace incomplete = new RunTrace(1, "s", false); incomplete.complete();
        assertThat(incomplete.snapshot().status()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.snapshot().usage()).isNull();
        RunTrace exhausted = new RunTrace(1, "s", false);
        exhausted.accept(new StreamEvent("EXCEED_MAX_ITERS", Map.of()));
        exhausted.accept(new StreamEvent("AGENT_END", Map.of())); exhausted.complete();
        assertThat(exhausted.snapshot().errorCode()).isEqualTo("MAX_ITERATIONS");
        assertThat(exhausted.snapshot().status()).isEqualTo("INCOMPLETE");
    }
    @Test void diagnosticsAreOwnerScopedAndEvictCompletedRuns() {
        var store = new RunTraceStore();
        var first = store.start(1, "s", false); first.complete();
        assertThatThrownBy(() -> store.require(first.id(), 2)).hasMessageContaining("404");
        assertThat(store.require(first.id(), 1).runId()).isEqualTo(first.id());
        for (int i = 0; i < 200; i++) store.start(1, "s", false).complete();
        assertThatThrownBy(() -> store.require(first.id(), 1)).hasMessageContaining("404");
    }
    @Test void leftoverInProgressTodoIsCompletedWhenFinalReportExists() {
        var trace = new RunTrace(1, "s", false);
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "todo", "toolCallName", "todoWrite", "state", "success",
                "metadata", Map.of("todos", java.util.List.of(
                        Map.of("content", "核对订单", "status", "completed"),
                        Map.of("content", "输出最终报告", "status", "in_progress"))))));
        trace.accept(new StreamEvent("TEXT_BLOCK_DELTA", Map.of("replyId", "final", "delta", "核心结论：2 月收入较 1 月增长。")));
        trace.accept(new StreamEvent("AGENT_END", Map.of()));
        trace.complete();
        assertThat(trace.snapshot().status()).isEqualTo("SUCCEEDED");
        assertThat(trace.snapshot().errorCode()).isNull();
        assertThat(trace.execution().snapshot().todos()).allSatisfy(todo ->
                assertThat(todo.path("status").asText()).isEqualTo("completed"));
        assertThat(trace.execution().snapshot().progress()).isEqualTo("分析完成");
    }

    @Test void leftoverPendingTodoStillMarksPlanIncompleteEvenIfReportExists() {
        var trace = new RunTrace(1, "s", false);
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "todo", "toolCallName", "todoWrite", "state", "success",
                "metadata", Map.of("todos", java.util.List.of(
                        Map.of("content", "核对订单", "status", "in_progress"),
                        Map.of("content", "输出最终报告", "status", "pending"))))));
        trace.accept(new StreamEvent("TEXT_BLOCK_DELTA", Map.of("replyId", "final", "delta", "核心结论：数据不足。")));
        trace.accept(new StreamEvent("AGENT_END", Map.of()));
        trace.complete();
        assertThat(trace.snapshot().status()).isEqualTo("INCOMPLETE");
        assertThat(trace.snapshot().errorCode()).isEqualTo("PLAN_INCOMPLETE");
        assertThat(trace.execution().snapshot().todos().getFirst().path("status").asText()).isEqualTo("in_progress");
        assertThat(trace.execution().snapshot().todos().getLast().path("status").asText()).isEqualTo("pending");
    }

    @Test void unfinishedPlanAndFrameworkErrorCannotBeReportedAsSuccess() {
        var trace = new RunTrace(1, "s", false);
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "todo", "toolCallName", "todoWrite", "state", "success",
                "metadata", Map.of("todos", java.util.List.of(Map.of("content", "待核对", "status", "in_progress"))))));
        trace.accept(new StreamEvent("AGENT_END", Map.of())); trace.complete();
        assertThat(trace.snapshot().status()).isEqualTo("INCOMPLETE");
        assertThat(trace.snapshot().errorCode()).isEqualTo("PLAN_INCOMPLETE");
        var failed = new RunTrace(1, "s", false);
        failed.accept(new StreamEvent("ERROR", Map.of("message", "private provider error")));
        failed.accept(new StreamEvent("AGENT_END", Map.of())); failed.complete();
        assertThat(failed.snapshot().status()).isEqualTo("FAILED");
        assertThat(failed.snapshot().errorCode()).isEqualTo("AGENT_FAILED");
        assertThat(failed.execution().snapshot().toString()).doesNotContain("private provider error");
    }
}
