package dev.qiqi.dataagent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TodoWriteAgentToolTest {
    private RuntimeContext context(String owner, String session) {
        var context = RuntimeContext.builder().userId(owner).sessionId(session).build();
        context.setAgentState(AgentState.builder().userId(owner).sessionId(session).build());
        return context;
    }
    private Map<String, String> task(String content, String status) { return Map.of("content", content, "status", status); }
    private io.agentscope.core.message.ToolResultBlock write(RuntimeContext context, Object todos) {
        return new TodoWriteAgentTool().callAsync(ToolCallParam.builder().input(Map.of("todos", todos)).runtimeContext(context).build()).block();
    }
    @Test void updatesPreserveTaskIdentityAndRejectInvalidPlanAtomically() {
        var context = context("1", "a");
        assertThat(write(context, List.of(task("查数", "in_progress"), task("报告", "pending"))).getState()).isEqualTo(ToolResultState.SUCCESS);
        String id = context.getAgentState().getTasksContext().getTasks().getFirst().getId();
        assertThat(write(context, List.of(task("查数", "in_progress"), task("报告", "in_progress"))).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(write(context, List.of(task("查数", "done"))).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(write(context, List.of(task("查数", "pending"), task("查数", "pending"))).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(write(context, List.of(task(" ", "pending"))).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(context.getAgentState().getTasksContext().getTasks()).hasSize(2);
        var result = write(context, List.of(task("查数", "completed"), task("报告", "in_progress")));
        assertThat(result.getMetadata()).containsKey("todos");
        assertThat(context.getAgentState().getTasksContext().getTasks().getFirst().getId()).isEqualTo(id);
        var restored = AgentState.fromJsonString(context.getAgentState().toJson());
        assertThat(restored.getTasksContext().getTasks().getFirst().getState().getWire()).isEqualTo("completed");
        assertThat(context("1", "b").getAgentState().getTasksContext().getTasks()).isEmpty();
        assertThat(context("2", "a").getAgentState().getTasksContext().getTasks()).isEmpty();
    }
}
