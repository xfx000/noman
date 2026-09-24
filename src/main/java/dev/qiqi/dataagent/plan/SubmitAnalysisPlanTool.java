package dev.qiqi.dataagent.plan;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 提交计划。权限检查返回 ASK，用户确认后框架才执行本工具。 */
@Component
public class SubmitAnalysisPlanTool extends ToolBase {
    public static final String NAME = "submit_analysis_plan";
    private static final String CONFIRMED = "分析计划已确认，可以开始查数。";
    private static final String NO_SESSION = "缺少会话标识，无法记录分析计划";

    private final AnalysisPlanGate gate;

    public SubmitAnalysisPlanTool(AnalysisPlanGate gate) {
        super(ToolBase.builder().name(NAME)
                .description("提交分析计划并等待用户确认。用户确认前不要查数。"
                        + "指标多于一个、有待用户认可的假设、或要出图表/报告时必须用这个工具。"
                        + "title 用用户原问题。sections 是编号章节，每章 2 到 4 条具体产出。"
                        + "deliverables 是最终输出，大约三条，每条有 title 和 detail。"
                        + "用户要求修改后，按反馈重写并再次调用本工具。")
                .inputSchema(schema()));
        this.gate = gate;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.ask("分析计划需要用户确认"));
    }

    @Override
    public java.util.List<io.agentscope.core.permission.PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        return List.of();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromSupplier(() -> save(param));
    }

    private ToolResultBlock save(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        if (context == null || context.getUserId() == null || context.getSessionId() == null)
            return ToolResultBlock.error(NO_SESSION);
        String feedback = text(param.getInput().get("userFeedback"));
        if (!feedback.isBlank()) {
            return ToolResultBlock.text("用户要求修改：" + feedback + "。请按反馈重写后再次调用 submit_analysis_plan");
        }
        AnalysisPlan plan = AnalysisPlan.fromInput(param.getInput());
        String invalid = AnalysisPlanReview.invalid(plan);
        if (invalid == null) invalid = AnalysisPlanReview.invalidOutline(plan);
        if (invalid != null) return ToolResultBlock.error(invalid);
        gate.accept(context.getUserId(), context.getSessionId(), plan);
        return ToolResultBlock.text(CONFIRMED);
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    private static Map<String, Object> schema() {
        var stringList = Map.of("type", "array", "items", Map.of("type", "string"));
        var section = Map.of("type", "object", "required", List.of("title", "items"),
                "properties", Map.of("title", Map.of("type", "string"), "items", stringList));
        var deliverable = Map.of("type", "object", "required", List.of("title", "detail"),
                "properties", Map.of("title", Map.of("type", "string"), "detail", Map.of("type", "string")));
        return Map.of("type", "object", "properties", Map.of(
                "metrics", stringList, "dimensions", stringList, "timeRange", Map.of("type", "string"),
                "tables", stringList, "assumptions", stringList, "outputs", stringList,
                "title", Map.of("type", "string"),
                "sections", Map.of("type", "array", "items", section),
                "deliverables", Map.of("type", "array", "items", deliverable),
                "userFeedback", Map.of("type", "string")));
    }
}
