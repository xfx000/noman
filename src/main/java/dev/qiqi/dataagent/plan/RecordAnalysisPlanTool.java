package dev.qiqi.dataagent.plan;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 不暂停。该确认时拒绝，并要求改用 submit_analysis_plan。 */
@Component
public class RecordAnalysisPlanTool implements AgentTool {
    public static final String NAME = "record_analysis_plan";
    private static final String RECORDED = "分析计划已记录，可以开始查数。";
    private static final String NO_SESSION = "缺少会话标识，无法记录分析计划";

    private final AnalysisPlanGate gate;

    public RecordAnalysisPlanTool(AnalysisPlanGate gate) {
        this.gate = gate;
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "记录分析计划并立即开始执行，不暂停。自动模式只用于简单单指标或不扩展指标及产出的追问；"
                + "快速模式可用于能通过数据核实假设的任务；只有需用户决定业务口径时应提交计划。先确认方案模式不可用。"
                + "不满足时本工具会拒绝，应改用 submit_analysis_plan。";
    }
    @Override public Map<String, Object> getParameters() {
        var stringList = Map.of("type", "array", "items", Map.of("type", "string"));
        return Map.of("type", "object", "required", List.of("metrics", "tables"), "properties", Map.of(
                "metrics", stringList, "dimensions", stringList, "timeRange", Map.of("type", "string"),
                "tables", stringList, "assumptions", stringList, "outputs", stringList));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromSupplier(() -> save(param));
    }

    private ToolResultBlock save(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        if (context == null || context.getUserId() == null || context.getSessionId() == null)
            return ToolResultBlock.error(NO_SESSION);
        AnalysisPlan plan = AnalysisPlan.fromInput(param.getInput());
        String invalid = AnalysisPlanReview.invalid(plan);
        if (invalid != null) return ToolResultBlock.error(invalid);
        AnalysisMode mode = context.get(AnalysisMode.class);
        String reject = AnalysisPlanReview.needsUserConfirmation(plan,
                gate.confirmed(context.getUserId(), context.getSessionId()), mode);
        if (reject != null) return ToolResultBlock.error(reject);
        gate.accept(context.getUserId(), context.getSessionId(), plan);
        return ToolResultBlock.text(RECORDED);
    }
}
