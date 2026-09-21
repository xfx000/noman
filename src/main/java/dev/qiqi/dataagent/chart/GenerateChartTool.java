package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.identity.UserIdentity;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Component
public class GenerateChartTool implements AgentTool {
    private final ChartProperties properties;
    private final ChartQueryStore queries;
    private final IdentityService identities;
    private final ChartMcpClient client;
    private final ChartArtifactStore artifacts;

    public GenerateChartTool(ChartProperties properties, ChartQueryStore queries, IdentityService identities, ChartMcpClient client) {
        this(properties, queries, identities, client, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GenerateChartTool(ChartProperties properties, ChartQueryStore queries, IdentityService identities, ChartMcpClient client, ChartArtifactStore artifacts) {
        this.properties = properties; this.queries = queries; this.identities = identities; this.client = client; this.artifacts = artifacts;
    }
    public boolean enabled() { return properties.enabled(); }
    @Override public String getName() { return "generate_chart"; }
    @Override public String getDescription() {
        return "Generate a final bar, line or pie chart from a verified execute_sql queryId selected after "
                + "the analysis evidence is complete. "
                + "Never ask the user whether to draw a chart. Select a categoryColumn and numeric valueColumn from the result. "
                + "Requires 1–100 complete rows and unique categories. Never pass raw data or SQL. "
                + "The chart is revealed with the final report; cite queryId in the answer.";
    }
    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("queryId", Map.of("type", "string"),
                        "type", Map.of("type", "string", "enum", List.of("bar", "line", "pie")),
                        "categoryColumn", Map.of("type", "string"), "valueColumn", Map.of("type", "string"),
                        "title", Map.of("type", "string", "maxLength", 100)),
                "required", List.of("queryId", "type", "categoryColumn", "valueColumn"));
    }
    @Override public boolean isReadOnly() { return true; }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            if (!enabled()) throw new IllegalStateException("图表功能未启用。");
            var context = param.getRuntimeContext();
            if (context == null || context.getUserId() == null || context.getSessionId() == null)
                throw new SecurityException("缺少服务端身份或会话。");
            identities.findActiveById(context.getUserId()).filter(UserIdentity::canQuery)
                    .orElseThrow(() -> new SecurityException("当前用户不能使用查询结果。"));
            var input = param.getInput();
            ChartSpec spec = new ChartSpec(string(input, "queryId"), string(input, "type"),
                    string(input, "categoryColumn"), string(input, "valueColumn"), string(input, "title"));
            var result = queries.require(context.getUserId(), context.getSessionId(), spec.queryId());
            return Map.entry(spec, spec.option(result));
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(entry -> client.render(entry.getKey(), entry.getValue()))
                .publishOn(Schedulers.boundedElastic())
                .map(artifact -> artifacts == null ? artifact : artifacts.save(param.getRuntimeContext().getUserId(), artifact))
                .map(artifact -> ToolResultBlock.of(
                        TextBlock.builder().text("图表产物已生成，将随最终报告展示：" + artifact.title()
                                + "；查询证据 queryId=" + artifact.queryId()).build(),
                        Map.of("chart", artifact)))
                .onErrorResume(error -> Mono.just(ToolResultBlock.error(
                        error instanceof IllegalArgumentException || error instanceof SecurityException
                                ? error.getMessage() : "图表生成失败或超时，请检查图表 MCP 服务后重试；已有查询结果仍可使用。")));
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }
}
