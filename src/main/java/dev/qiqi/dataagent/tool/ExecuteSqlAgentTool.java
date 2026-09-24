package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.plan.AnalysisPlanGate;
import dev.qiqi.dataagent.plan.PlanExecutionGuard;
import dev.qiqi.dataagent.identity.UserIdentity;
import dev.qiqi.dataagent.query.ReadOnlyQueryService;
import dev.qiqi.dataagent.chart.ChartQueryStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * AgentScope 与 Qiqi 查询服务之间的适配器。
 *
 * <p>AgentScope 决定何时调用 execute_sql；本类负责把 Tool Call 转成受身份和数据范围约束的
 * 查询。核心 SQL 安全逻辑仍然位于 ReadOnlyQueryService 及其下游服务中。</p>
 */
@Component
public class ExecuteSqlAgentTool implements AgentTool {
    // 只允许模型提交 SQL。用户和部门不能成为模型参数，防止模型伪造身份绕过权限。
    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of("sql", Map.of("type", "string", "description", "Validated SELECT or WITH query")),
            "required", List.of("sql"));

    private final IdentityService identities;
    private final ReadOnlyQueryService queries;
    private final ObjectMapper mapper;
    private final ChartQueryStore chartQueries;
    private final AnalysisPlanGate plans;

    public ExecuteSqlAgentTool(IdentityService identities, ReadOnlyQueryService queries, ObjectMapper mapper, ChartQueryStore chartQueries, AnalysisPlanGate plans) {
        this.identities = identities;
        this.queries = queries;
        this.mapper = mapper;
        this.chartQueries = chartQueries;
        this.plans = plans;
    }

    @Override public String getName() { return "execute_sql"; }
    @Override public String getDescription() {
        return "Execute one validated read-only SQL query. User identity and data scope are supplied by the server, never by model arguments.";
    }
    @Override public Map<String, Object> getParameters() { return PARAMETERS; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // JDBC 是阻塞调用，切到 boundedElastic，避免占用 WebFlux/AgentScope 的事件线程。
        return Mono.fromCallable(() -> execute(param)).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> Mono.just(ToolResultBlock.error(message(error))));
    }

    private ToolResultBlock execute(ToolCallParam param) throws JsonProcessingException {
        // RuntimeContext 由 DataAgentService 在服务端创建，随后由 AgentScope 原样传递给工具。
        RuntimeContext context = param.getRuntimeContext();
        if (context == null || context.getUserId() == null || context.getSessionId() == null) {
            throw new SecurityException("Authenticated user and conversation are required");
        }
        var blocked = PlanExecutionGuard.block(context, plans);
        if (blocked != null) return blocked;

        // 不直接相信上下文中的字符串：重新查询有效用户，并再次检查该用户是否允许查数。
        UserIdentity identity = identities.findActiveById(context.getUserId())
                .filter(UserIdentity::canQuery)
                .orElseThrow(() -> new SecurityException("User cannot query data"));

        // 从这里往下进入 Qiqi 自己的业务层：SQL 校验、范围注入、只读执行和审计。
        Object rawSql = param.getInput().get("sql");
        if (!(rawSql instanceof String sql) || sql.isBlank()) throw new IllegalArgumentException("sql is required");
        var cancellation = context.get(dev.qiqi.dataagent.agent.RunCancellation.class);
        var result = queries.execute(sql, identity, context.getSessionId(),
                cancellation == null ? new dev.qiqi.dataagent.agent.RunCancellation() : cancellation);
        chartQueries.remember(context.getUserId(), context.getSessionId(), result);
        var summary = mapper.valueToTree(result);
        ((com.fasterxml.jackson.databind.node.ObjectNode) summary).remove("rows");
        return ToolResultBlock.of(io.agentscope.core.message.TextBlock.builder().text(mapper.writeValueAsString(result)).build(),
                Map.of("evidence", summary));
    }

    private static String message(Throwable error) {
        String raw = error.getMessage();
        if (raw == null || raw.isBlank()) raw = error.getClass().getSimpleName();
        return raw.length() <= 500 ? raw : raw.substring(0, 500);
    }
}
