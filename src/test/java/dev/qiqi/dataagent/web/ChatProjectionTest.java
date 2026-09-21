package dev.qiqi.dataagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.agent.*;
import dev.qiqi.dataagent.chart.ChartProperties;
import dev.qiqi.dataagent.identity.*;
import dev.qiqi.dataagent.network.WebSearchProperties;
import dev.qiqi.dataagent.observability.RunTraceStore;
import io.agentscope.core.event.*;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatProjectionTest {
    private final DataAgentService agent = mock(DataAgentService.class);
    private final RunTraceStore store = new RunTraceStore();
    ChatController controller(Duration timeout) {
        var identities = mock(IdentityService.class);
        when(identities.findActiveByUsername("admin")).thenReturn(Optional.of(new UserIdentity(1, "admin", "Admin", "ALL", null)));
        when(agent.modelConfigured()).thenReturn(true);
        return new ChatController(agent, identities, new AgentEventMapper(new ObjectMapper()), new ChartProperties(false, java.net.URI.create("http://localhost:3033/mcp"), Duration.ofSeconds(30)),
                new WebSearchProperties(false, "", Duration.ofSeconds(15)), store, new RunCoordinator(), timeout);
    }
    @Test void controllerPublishesOnlySafeToolProjectionAndPreservesOwnerScopedJournal() {
        when(agent.stream(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(Flux.just(
                new ThinkingBlockDeltaEvent("r", "b", "先核对已付款口径 token=private-credential"),
                new ToolCallStartEvent("r", "call", "lookup"),
                new ToolCallDeltaEvent("r", "call", "lookup", "{\"token\":\"private-credential\",\"query\":\"public\"}"),
                new ToolCallEndEvent("r", "call", "lookup"),
                new ToolResultTextDeltaEvent("r", "call", "lookup", "{\"value\":42}"),
                new ToolResultEndEvent("r", "call", "lookup", ToolResultState.SUCCESS),
                new TextBlockDeltaEvent("r", "answer", "公开结论"), new AgentEndEvent("r")));
        var events = controller(Duration.ofSeconds(5)).stream("admin", new ChatRequest("test", "session", false))
                .map(sse -> sse.data()).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(StreamEvent::type).contains("RUN_START", "EXECUTION_UPDATE", "TEXT_BLOCK_DELTA", "RUN_END")
                .doesNotContain("THINKING_BLOCK_DELTA", "TOOL_CALL_DELTA", "TOOL_RESULT_TEXT_DELTA");
        assertThat(events.toString()).doesNotContain("private-credential", "先核对已付款口径")
                .contains("redacted", "公开结论");
        var end = (dev.qiqi.dataagent.observability.RunTrace.Snapshot) events.getLast().data().get("run");
        assertThat(end.status()).isEqualTo("SUCCEEDED");
        var execution = store.execution(end.runId(), 1);
        assertThat(execution.tools().getFirst().result()).contains("42");
        assertThat(execution.toString()).doesNotContain("private-credential", "先核对已付款口径");
        assertThat(execution.narrations()).extracting(dev.qiqi.dataagent.observability.ExecutionJournal.Narration::kind)
                .containsOnly("text");
        assertThat(execution.finalNarrationId()).isEqualTo("text:r");
        assertThatThrownBy(() -> store.execution(end.runId(), 2)).hasMessageContaining("404");
    }
    @Test void persistFailuresDoNotFailACompletedRun() {
        var workspace = new dev.qiqi.dataagent.storage.LocalWorkspace(
                new dev.qiqi.dataagent.storage.StorageProperties(java.nio.file.Path.of(".")), new ObjectMapper()) {
            @Override public synchronized void write(String owner, String collection, String id, Object value) {
                throw new IllegalStateException("Unable to persist workspace data");
            }
        };
        var failing = new RunTraceStore(workspace);
        var identities = mock(IdentityService.class);
        when(identities.findActiveByUsername("admin")).thenReturn(Optional.of(new UserIdentity(1, "admin", "Admin", "ALL", null)));
        when(agent.modelConfigured()).thenReturn(true);
        when(agent.stream(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(Flux.just(
                new TextBlockDeltaEvent("r", "answer", "公开结论"), new AgentEndEvent("r")));
        var events = new ChatController(agent, identities, new AgentEventMapper(new ObjectMapper()),
                new ChartProperties(false, java.net.URI.create("http://localhost:3033/mcp"), Duration.ofSeconds(30)),
                new WebSearchProperties(false, "", Duration.ofSeconds(15)), failing, new RunCoordinator(), Duration.ofSeconds(5))
                .stream("admin", new ChatRequest("test", "session", false))
                .map(sse -> sse.data()).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(StreamEvent::type).contains("RUN_END").doesNotContain("ERROR");
        var end = (dev.qiqi.dataagent.observability.RunTrace.Snapshot) events.getLast().data().get("run");
        assertThat(end.status()).isEqualTo("SUCCEEDED");
    }
    @Test void totalDeadlineStopsAnOtherwiseContinuouslyStreamingRun() {
        var cancelled = new AtomicBoolean();
        when(agent.stream(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(
                Flux.interval(Duration.ofMillis(10)).map(tick -> (AgentEvent) new TextBlockDeltaEvent("r", "b", "progress"))
                        .doOnCancel(() -> cancelled.set(true)));
        var events = controller(Duration.ofMillis(150)).stream("admin", new ChatRequest("long", "deadline", false))
                .map(sse -> sse.data()).collectList().block(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
        assertThat(events.toString()).contains("RUN_TIMEOUT", "ERROR", "RUN_END");
        var end = (dev.qiqi.dataagent.observability.RunTrace.Snapshot) events.getLast().data().get("run");
        assertThat(end.status()).isEqualTo("FAILED");
        assertThat(store.execution(end.runId(), 1).status()).isEqualTo("FAILED");
    }
}
