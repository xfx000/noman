package dev.qiqi.dataagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AgentScope 用 content 里的 JSON 做参数校验。部分模型只填 input，content 为空时
 * 校验会报 argument content is null，计划无法真正提交。
 */
public final class ToolArgumentContent implements MiddlewareBase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) calls.add(ensure(call));
        return next.apply(new ActingInput(calls));
    }

    public static ToolUseBlock ensure(ToolUseBlock call) {
        if (call.getContent() != null && !call.getContent().isBlank()) return call;
        Map<String, Object> args = call.getInput() == null ? Map.of() : call.getInput();
        String raw;
        try {
            raw = JSON.writeValueAsString(args);
        } catch (Exception error) {
            raw = "{}";
        }
        ToolUseBlock.Builder builder = ToolUseBlock.builder().id(call.getId()).name(call.getName()).input(args).content(raw);
        if (call.getMetadata() != null) builder.metadata(call.getMetadata());
        if (call.getState() != null) builder.state(call.getState());
        return builder.build();
    }

    public static String json(Map<String, Object> args) {
        try {
            return JSON.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception error) {
            return "{}";
        }
    }
}
