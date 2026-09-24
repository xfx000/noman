package dev.qiqi.dataagent.plan;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves AgentScope ASK pauses a custom tool and a modified ConfirmResult reaches the tool. */
class AskPermissionSpikeTest {
    @Test
    void askPausesUntilConfirmAndCarriesEditedInput() {
        var seen = new ArrayList<Map<String, Object>>();
        var calls = new AtomicInteger();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(probe(seen));
        var agent = ReActAgent.builder()
                .name("ask-spike")
                .model(script(calls))
                .toolkit(toolkit)
                .enableMetaTool(false)
                .maxIters(4)
                .stateStore(new InMemoryAgentStateStore())
                .build();
        var context = RuntimeContext.builder().userId("1").sessionId("spike").build();

        var paused = agent.streamEvents(new UserMessage("go"), context).collectList().block(Duration.ofSeconds(15));
        assertThat(paused.stream().map(event -> event.getClass().getSimpleName()).toList())
                .as("seen=%s calls=%s", seen, calls.get()).contains("RequireUserConfirmEvent");
        var confirm = paused.stream().filter(RequireUserConfirmEvent.class::isInstance)
                .map(RequireUserConfirmEvent.class::cast).findFirst().orElseThrow();
        assertThat(seen).isEmpty();
        assertThat(confirm.getToolCalls()).hasSize(1);
        assertThat(paused).noneMatch(ToolResultEndEvent.class::isInstance);

        var original = confirm.getToolCalls().getFirst();
        var edited = new HashMap<>(original.getInput());
        edited.put("userFeedback", "改成按城市看");
        var resumedCall = ToolUseBlock.builder().id(original.getId()).name(original.getName())
                .input(edited).content(original.getContent()).build();
        var resume = UserMessage.builder().textContent("改成按城市看")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, resumedCall))))
                .build();
        var continued = agent.streamEvents(resume, context).collectList().block(Duration.ofSeconds(15));

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).containsEntry("userFeedback", "改成按城市看");
        assertThat(continued).anyMatch(ToolResultEndEvent.class::isInstance);
    }

    private static ToolBase probe(List<Map<String, Object>> seen) {
        return new ToolBase(ToolBase.builder().name("probe_tool").description("Probe that records its input.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "note", Map.of("type", "string"),
                        "userFeedback", Map.of("type", "string"))))) {
            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
                return Mono.just(PermissionDecision.ask("plan needs confirmation"));
            }
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                seen.add(param.getInput());
                var feedback = String.valueOf(param.getInput().getOrDefault("userFeedback", ""));
                return Mono.just(ToolResultBlock.text("ran:" + feedback));
            }
        };
    }

    private static Model script(AtomicInteger calls) {
        return new Model() {
            public String getModelName() { return "ask-spike"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    return Flux.just(ChatResponse.builder().id("call").finishReason("tool_calls")
                            .content(List.of(ToolUseBlock.builder().id("call-1").name("probe_tool")
                                    .input(Map.of("note", "first")).content("{\"note\":\"first\"}").build()))
                            .build());
                }
                return Flux.just(ChatResponse.builder().id("done").finishReason("stop")
                        .content(List.of(TextBlock.builder().text("done").build())).build());
            }
        };
    }
}
