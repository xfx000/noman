package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.identity.UserIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerateChartToolTest {
    @Test void forgedQueryAndMissingIdentityNeverReachMcpAndOutageIsAnError() {
        var identities = mock(IdentityService.class);
        var client = mock(ChartMcpClient.class);
        var store = new ChartQueryStore();
        store.remember("2", "session", ChartSpecTest.sample());
        when(identities.findActiveById("2")).thenReturn(Optional.of(new UserIdentity(2L,"alice","Alice","DEPARTMENT",10L)));
        var tool = new GenerateChartTool(new ChartProperties(true, URI.create("http://localhost:3033/mcp"), Duration.ofSeconds(1)), store, identities, client);
        var input = Map.<String,Object>of("queryId","q1","type","bar","categoryColumn","month","valueColumn","revenue","userId","2");
        for (var context : new RuntimeContext[]{RuntimeContext.empty(), RuntimeContext.builder().userId("2").sessionId("other").build()}) {
            var result = tool.callAsync(ToolCallParam.builder().input(input).runtimeContext(context).build()).block(Duration.ofSeconds(5));
            assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        }
        verifyNoInteractions(client);
        when(client.render(any(), any())).thenReturn(Mono.error(new java.util.concurrent.TimeoutException()));
        var result = tool.callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().userId("2").sessionId("session").build()).build()).block(Duration.ofSeconds(5));
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(result.getMetadata()).doesNotContainKey("chart");
    }
}
