package dev.qiqi.dataagent.web;

import dev.qiqi.dataagent.plan.AnalysisMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 8_000) String query,
        @Size(max = 120) String conversationId,
        boolean online,
        PlanDecision plan,
        AnalysisMode analysisMode) {
    public ChatRequest {
        analysisMode = AnalysisMode.orAuto(analysisMode);
    }
    public ChatRequest(String query, String conversationId, boolean online) {
        this(query, conversationId, online, null, AnalysisMode.AUTO);
    }
    public ChatRequest(String query, String conversationId, boolean online, PlanDecision plan) {
        this(query, conversationId, online, plan, AnalysisMode.AUTO);
    }

    /** 计划确认。confirmed 为 false 时 feedback 是修改意见。 */
    public record PlanDecision(String toolCallId, boolean confirmed, @Size(max = 8_000) String feedback) {}
}
