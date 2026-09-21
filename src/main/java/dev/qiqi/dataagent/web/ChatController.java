package dev.qiqi.dataagent.web;

import dev.qiqi.dataagent.agent.DataAgentService;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.identity.UserIdentity;
import dev.qiqi.dataagent.chart.ChartProperties;
import jakarta.validation.Valid;
import dev.qiqi.dataagent.network.WebSearchProperties;
import dev.qiqi.dataagent.observability.RunTrace;
import dev.qiqi.dataagent.observability.RunTraceStore;
import org.springframework.web.bind.annotation.PathVariable;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.SignalType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final DataAgentService agent;
    private final IdentityService identities;
    private final AgentEventMapper eventMapper;
    private final ChartProperties charts;
    private final WebSearchProperties webSearch;
    private final RunTraceStore traces;
    private final dev.qiqi.dataagent.agent.RunCoordinator coordinator;
    private final Duration runTimeout;

    public ChatController(DataAgentService agent, IdentityService identities, AgentEventMapper eventMapper, ChartProperties charts, WebSearchProperties webSearch, RunTraceStore traces, dev.qiqi.dataagent.agent.RunCoordinator coordinator,
                          @org.springframework.beans.factory.annotation.Value("${qiqi.model.run-timeout:10m}") Duration runTimeout) {
        this.agent = agent;
        this.identities = identities;
        this.eventMapper = eventMapper;
        this.charts = charts;
        this.webSearch = webSearch;
        this.traces = traces;
        this.coordinator = coordinator;
        if (runTimeout.isNegative() || runTimeout.isZero() || runTimeout.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException("run-timeout must be positive and at most 30 minutes");
        this.runTimeout = runTimeout;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> stream(
            @RequestHeader("X-Qiqi-User") String username,
            @Valid @RequestBody ChatRequest request) {
        // 先在服务端确认身份，再进入 Agent。当前请求头只是演示入口，生产环境应替换成 SSO/JWT。
        UserIdentity identity = identities.findActiveByUsername(username)
                .orElseThrow(() -> new SecurityException("Unknown or inactive demo user"));

        // conversationId 对应 AgentScope 的 sessionId；未提供时创建新会话，提供时继续旧会话。
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? "qiqi-" + UUID.randomUUID() : request.conversationId().trim();

        if (request.online() && !webSearch.configured())
            throw new IllegalArgumentException("联网搜索尚未配置，请关闭联网开关或配置服务端 Tavily Key。");

        return Flux.defer(() -> {
            RunTrace trace = traces.start(identity.id(), conversationId, request.online());
            dev.qiqi.dataagent.agent.RunCoordinator.Handle handle;
            try { handle = coordinator.start(trace); }
            catch (RuntimeException error) { trace.fail("SESSION_BUSY"); traces.persist(trace); throw error; }
            long[] lastSaved = {0}, lastPublished = {0};
            // A total deadline applies even when tools keep producing events.
            long deadlineNanos = System.nanoTime() + runTimeout.toNanos();
            Flux<StreamEvent> events = Flux.defer(() -> agent.stream(request.query().trim(), conversationId, identity, request.online(), handle.cancellation()))
                    .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .map(eventMapper::map)
                    .concatMap(event -> {
                        trace.accept(event);
                        long now = System.nanoTime();
                        boolean boundary = event.type().endsWith("_END") || event.type().equals("TOOL_RESULT_START")
                                || event.type().equals("TOOL_CALL_START") || event.type().equals("EXCEED_MAX_ITERS");
                        if (boundary || now - lastSaved[0] > 1_000_000_000L) { traces.persist(trace); lastSaved[0] = now; }
                        boolean publish = boundary || now - lastPublished[0] > 250_000_000L;
                        if (publish) lastPublished[0] = now;
                        // Tool/thinking/provider payloads stay behind the normalized, sanitized projection.
                        boolean forward = java.util.Set.of("TEXT_BLOCK_DELTA", "AGENT_END", "EXCEED_MAX_ITERS").contains(event.type());
                        if (forward && publish) return Flux.just(event, executionEvent(trace));
                        if (forward) return Flux.just(event);
                        return publish ? Flux.just(executionEvent(trace)) : Flux.empty();
                    })
                    .timeout(reactor.core.publisher.Mono.delay(runTimeout), ignored ->
                            reactor.core.publisher.Mono.delay(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()))))
                    .timeout(Duration.ofMinutes(2))
                    .onErrorResume(error -> {
                        String code = !agent.modelConfigured() ? "MODEL_NOT_CONFIGURED"
                                : error instanceof TimeoutException ? "RUN_TIMEOUT" : "AGENT_FAILED";
                        trace.fail(code);
                        handle.cancellation().cancel();
                        String message = code.equals("MODEL_NOT_CONFIGURED") ? "模型尚未配置，请先配置模型 Key。"
                                : code.equals("RUN_TIMEOUT") ? "分析达到时间限制，已保留进度与证据，可继续剩余任务。" : "分析执行失败，请凭运行编号检查服务端配置与服务状态。";
                        return Flux.just(new StreamEvent("ERROR", Map.of("message", message, "code", code, "runId", trace.id())));
                    });
            return Flux.concat(Flux.just(new StreamEvent("RUN_START", Map.of("run", trace.snapshot()))), events,
                            Flux.defer(() -> { trace.complete(); traces.persist(trace); return Flux.just(executionEvent(trace), new StreamEvent("RUN_END", Map.of("run", trace.snapshot()))); }))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) { trace.cancel(); handle.cancellation().cancel(); }
                        try { traces.persist(trace); } finally { coordinator.release(handle); }
                    });
        }).map(event -> ServerSentEvent.<StreamEvent>builder(event).event(event.type()).build());
    }

    private StreamEvent executionEvent(RunTrace trace) {
        return new StreamEvent("EXECUTION_UPDATE", Map.of("execution", trace.execution().snapshot()));
    }

    @GetMapping("/runs/{runId}/execution")
    public dev.qiqi.dataagent.observability.ExecutionJournal.Snapshot execution(
            @RequestHeader("X-Qiqi-User") String username, @PathVariable String runId) {
        UserIdentity identity = identities.findActiveByUsername(username)
                .orElseThrow(() -> new SecurityException("Unknown or inactive demo user"));
        return traces.execution(runId, identity.id());
    }

    @GetMapping("/runs/{runId}")
    public RunTrace.Snapshot run(@RequestHeader("X-Qiqi-User") String username, @PathVariable String runId) {
        UserIdentity identity = identities.findActiveByUsername(username)
                .orElseThrow(() -> new SecurityException("Unknown or inactive demo user"));
        return traces.require(runId, identity.id());
    }

    @PostMapping("/runs/{runId}/cancel")
    public RunTrace.Snapshot cancel(@RequestHeader("X-Qiqi-User") String username, @PathVariable String runId) {
        UserIdentity identity = identities.findActiveByUsername(username)
                .orElseThrow(() -> new SecurityException("Unknown or inactive demo user"));
        traces.require(runId, identity.id());
        coordinator.cancel(runId, identity.id());
        return traces.require(runId, identity.id());
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        List<Map<String, Object>> users = identities.listDemoUsers().stream().map(user -> Map.<String, Object>of(
                "username", user.username(), "displayName", user.displayName(),
                "dataScope", user.dataScope(), "departmentId", user.departmentId() == null ? "" : user.departmentId())).toList();
        return Map.of("name", "Qiqi DataAgent", "modelConfigured", agent.modelConfigured(), "demoUsers", users,
                "chartEnabled", charts.enabled(), "webSearchEnabled", webSearch.configured(), "toolDiscoveryEnabled", true, "workspaceEnabled", true, "executionEnabled", true);
    }

}
