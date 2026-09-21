package dev.qiqi.dataagent.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.tool.ExecuteSqlAgentTool;
import dev.qiqi.dataagent.web.AgentEventMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: a real mcp-echarts server and the real AgentScope loop, with deterministic model decisions. */
@SpringBootTest(properties = {"qiqi.chart.enabled=true", "qiqi.chart.mcp-url=${QIQI_CHART_TEST_URL:http://localhost:3334/mcp}"})
@EnabledIfEnvironmentVariable(named = "QIQI_LIVE_CHART_TEST", matches = "true")
class ChartMcpLiveTest {
    @Autowired ExecuteSqlAgentTool executeSql;
    @Autowired GenerateChartTool charts;
    @Autowired ChartArtifactStore artifacts;
    @Autowired ObjectMapper json;
    @Autowired AgentEventMapper events;

    @Test void realQueryAndMcpProduceAllThreeChartsInAgentStream() throws Exception {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(executeSql);
        toolkit.registerAgentTool(charts);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> queryId = new AtomicReference<>();
        Model decisions = new Model() {
            public String getModelName() { return "chart-integration-script"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int step = calls.getAndIncrement();
                if (step == 0) return invoke("execute_sql", Map.of("sql",
                        "SELECT department_id AS department, SUM(total_amount) AS revenue FROM sales_order WHERE status = 'PAID' GROUP BY department_id ORDER BY department_id"), step);
                if (step == 1) {
                    var result = messages.stream().flatMap(message -> message.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                            .filter(tool -> "execute_sql".equals(tool.getName())).findFirst().orElseThrow();
                    try { queryId.set(json.readTree(((TextBlock) result.getOutput().getFirst()).getText()).get("queryId").asText()); }
                    catch (Exception e) { throw new AssertionError(e); }
                }
                if (step <= 3) return invoke("generate_chart", Map.of("queryId",queryId.get(),"type",List.of("bar","line","pie").get(step-1),
                        "categoryColumn","department","valueColumn","revenue","title","各部门已付款收入"),step);
                return Flux.just(ChatResponse.builder().id("answer").finishReason("stop")
                        .content(List.of(TextBlock.builder().text("已按查询证据生成三种图表。北区 27,200，南区 24,800；queryId=" + queryId.get()).build())).build());
            }
            private Flux<ChatResponse> invoke(String name, Map<String,Object> args, int step) {
                try {
                    return Flux.just(ChatResponse.builder().id("reply-" + step).finishReason("tool_calls").content(List.of(
                            ToolUseBlock.builder().id("call-" + step).name(name).input(args).content(json.writeValueAsString(args)).build())).build());
                } catch (Exception e) { throw new AssertionError(e); }
            }
        };
        var agent = ReActAgent.builder().name("chart-test").model(decisions).toolkit(toolkit)
                .enableMetaTool(false).maxIters(6).stateStore(new InMemoryAgentStateStore()).build();
        var stream = agent.streamEvents(new UserMessage("用三种图表展示各部门收入"),
                RuntimeContext.builder().userId("1").sessionId("chart-live-test").build())
                .collectList().block(Duration.ofSeconds(100));
        var completed = stream.stream().filter(ToolResultEndEvent.class::isInstance).map(ToolResultEndEvent.class::cast)
                .filter(event -> "generate_chart".equals(event.getToolCallName())).toList();
        assertThat(completed).hasSize(3).allSatisfy(event -> {
            assertThat(event.getState()).isNotEqualTo(ToolResultState.ERROR);
            assertThat(event.getMetadata()).containsKey("chart");
        });
        Path output = Path.of("target/chart-integration");
        Files.createDirectories(output);
        for (var event : completed) {
            ChartArtifact artifact = json.convertValue(event.getMetadata().get("chart"), ChartArtifact.class);
            assertThat(artifact.queryId()).isEqualTo(queryId.get());
            artifact = artifacts.require("1", artifact.id());
            assertThat(artifact.source()).startsWith("data:image/png;base64,");
            Files.write(output.resolve(artifact.type() + ".png"), Base64.getDecoder().decode(artifact.source().split(",", 2)[1]));
        }
        json.writeValue(output.resolve("events.json").toFile(), stream.stream().map(events::map).toList());
        assertThat(calls).hasValue(5);
    }
}
