package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.chart.ChartQueryStore;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.identity.UserIdentity;
import dev.qiqi.dataagent.query.ReadOnlyQueryService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecuteSqlAgentToolTest {
    @Test void queryExceptionsBecomeToolErrorsInsteadOfAbortingTheLoop() {
        var identities = mock(IdentityService.class);
        var queries = mock(ReadOnlyQueryService.class);
        when(identities.findActiveById("1")).thenReturn(Optional.of(new UserIdentity(1, "admin", "Admin", "ALL", null)));
        when(queries.execute(eq("SELECT 1"), any(), eq("s"), any()))
                .thenThrow(new IllegalArgumentException("Table is not exposed to the agent: ghost"));
        var result = new ExecuteSqlAgentTool(identities, queries, new ObjectMapper(), new ChartQueryStore())
                .callAsync(ToolCallParam.builder()
                        .input(Map.of("sql", "SELECT 1"))
                        .runtimeContext(RuntimeContext.builder().userId("1").sessionId("s").build())
                        .build())
                .block();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        String text = result.getOutput().stream()
                .filter(TextBlock.class::isInstance).map(block -> ((TextBlock) block).getText())
                .findFirst().orElse(result.toString());
        assertThat(text).contains("ghost");
    }
}
