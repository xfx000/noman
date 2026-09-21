package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.network.NetworkAccess;
import dev.qiqi.dataagent.network.WebSearchClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"qiqi.chart.enabled=true", "qiqi.web-search.enabled=true", "qiqi.web-search.api-key=test-key"})
class DataAgentToolkitTest {
    @Autowired DataAgentToolkit toolkits;
    @Autowired ObjectMapper json;
    @MockitoBean WebSearchClient web;

    @Test void nativeDiscoveryActivatesOptionalSchemasAndOfflineTurnCannotReuseOnlineTool() {
        when(web.search("public research")).thenReturn(Mono.just(new WebSearchClient.SearchResult("Tavily", "now", List.of())));
        AtomicInteger calls = new AtomicInteger();
        Model decisions = new Model() {
            public String getModelName() { return "discovery-script"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int step = calls.getAndIncrement();
                var names = tools.stream().map(ToolSchema::getName).toList();
                assertThat(names).contains("execute_sql", "reset_equipped_tools", "generate_chart", "todoWrite");
                if (step == 0) {
                    assertThat(names).doesNotContain("web_search");
                    return invoke("reset_equipped_tools", Map.of("to_activate", List.of("web")), step);
                }
                if (step == 1) {
                    assertThat(names).contains("generate_chart", "web_search");
                    return invoke("web_search", Map.of("query", "public research"), step);
                }
                return Flux.just(ChatResponse.builder().id("answer").finishReason("stop")
                        .content(List.of(TextBlock.builder().text("Done").build())).build());
            }
            private Flux<ChatResponse> invoke(String name, Map<String,Object> args, int step) {
                try { return Flux.just(ChatResponse.builder().id("reply-" + step).finishReason("tool_calls").content(List.of(
                        ToolUseBlock.builder().id("call-" + step).name(name).input(args).content(json.writeValueAsString(args)).build())).build()); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
        var store = new InMemoryAgentStateStore();
        var agent = ReActAgent.builder().name("discovery-test").model(decisions).toolkit(toolkits.create(true))
                .enableMetaTool(true).maxIters(4).stateStore(store).build();
        var events = agent.streamEvents(new UserMessage("research"), RuntimeContext.builder().userId("1").sessionId("s")
                .put(NetworkAccess.class, new NetworkAccess(true)).build()).collectList().block(Duration.ofSeconds(20));
        assertThat(events.stream().filter(ToolResultEndEvent.class::isInstance).map(ToolResultEndEvent.class::cast).toList())
                .hasSize(2).allSatisfy(event -> assertThat(event.getState()).isEqualTo(ToolResultState.SUCCESS));
        verify(web).search("public research");
        var offline = toolkits.create(false);
        assertThat(offline.getTool("generate_chart")).isNotNull();
        assertThat(offline.getTool("web_search")).isNull();
        assertThat(offline.getToolSchemas(List.of("web")).stream().map(ToolSchema::getName)).doesNotContain("web_search");
    }
}
