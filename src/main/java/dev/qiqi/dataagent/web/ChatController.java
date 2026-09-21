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

    public ChatController(DataAgentService agent, IdentityService identities, AgentEventMapper eventMapper, ChartProperties charts, WebSearchProperties webSearch, RunTraceStore traces, dev.qiqi.dataagent.agent.RunCoordinator coordinator) {
        this.agent = agent;
        this.identities = identities;
        this.eventMapper = eventMapper;
        this.charts = charts;
        this.webSearch = webSearch;
        this.traces = traces;
        this.coordinator = coordinator;
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
            Flux<StreamEvent> events = Flux.defer(() -> agent.stream(request.query().trim(), conversationId, identity, request.online(), handle.cancellation()))
                    .map(eventMapper::map).doOnNext(trace::accept)
                    .timeout(Duration.ofMinutes(2))
                    .onErrorResume(error -> {
                        String code = !agent.modelConfigured() ? "MODEL_NOT_CONFIGURED"
                                : error instanceof TimeoutException ? "RUN_TIMEOUT" : "AGENT_FAILED";
                        trace.fail(code);
                        handle.cancellation().cancel();
                        String message = code.equals("MODEL_NOT_CONFIGURED") ? "模型尚未配置，请先配置模型 Key。"
                                : code.equals("RUN_TIMEOUT") ? "等待响应超时，请稍后重试。" : "分析执行失败，请凭运行编号检查服务端配置与服务状态。";
                        return Flux.just(new StreamEvent("ERROR", Map.of("message", message, "code", code, "runId", trace.id())));
                    });
            return Flux.concat(Flux.just(new StreamEvent("RUN_START", Map.of("run", trace.snapshot()))), events,
                            Flux.defer(() -> { trace.complete(); return Flux.just(new StreamEvent("RUN_END", Map.of("run", trace.snapshot()))); }))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) { trace.cancel(); handle.cancellation().cancel(); }
                        try { traces.persist(trace); } finally { coordinator.release(handle); }
                    });
        }).map(event -> ServerSentEvent.<StreamEvent>builder(event).event(event.type()).build());
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
                "chartEnabled", charts.enabled(), "webSearchEnabled", webSearch.configured(), "toolDiscoveryEnabled", true, "workspaceEnabled", true);
    }

}
