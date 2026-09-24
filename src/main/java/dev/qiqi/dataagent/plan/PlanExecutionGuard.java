package dev.qiqi.dataagent.plan;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;

/** 本轮计划未确认时，执行类工具直接拒绝。 */
public final class PlanExecutionGuard {
    private PlanExecutionGuard() {}

    public static ToolResultBlock block(RuntimeContext context, AnalysisPlanGate gate) {
        if (context == null || context.getUserId() == null || context.getSessionId() == null) return null;
        if (gate.isRoundOpen(context.getUserId(), context.getSessionId())) return null;
        return ToolResultBlock.error(AnalysisPlanGate.BLOCKED_MESSAGE);
    }
}
