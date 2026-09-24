package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.plan.AnalysisPlanGate;
import dev.qiqi.dataagent.plan.PlanExecutionGuard;
import dev.qiqi.dataagent.query.SqlPolicy;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class ValidateSqlAgentTool implements AgentTool {
    private final SqlPolicy policy;
    private final AnalysisPlanGate gate;
    private final ObjectMapper mapper;

    public ValidateSqlAgentTool(SqlPolicy policy, AnalysisPlanGate gate, ObjectMapper mapper) {
        this.policy = policy;
        this.gate = gate;
        this.mapper = mapper;
    }

    @Override public String getName() { return "validate_sql"; }
    @Override public String getDescription() {
        return "Validate one read-only SQL statement, enforce the table allowlist and cap its LIMIT. Call before execute_sql.";
    }
    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("sql"),
                "properties", Map.of("sql", Map.of("type", "string", "description", "A single SELECT or WITH query")));
    }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromSupplier(() -> {
            var blocked = PlanExecutionGuard.block(param.getRuntimeContext(), gate);
            if (blocked != null) return blocked;
            Object raw = param.getInput().get("sql");
            if (!(raw instanceof String sql) || sql.isBlank()) return ToolResultBlock.error("sql is required");
            try {
                return ToolResultBlock.text(mapper.writeValueAsString(policy.validate(sql)));
            } catch (JsonProcessingException e) {
                return ToolResultBlock.error("Unable to serialize tool result");
            }
        });
    }
}
