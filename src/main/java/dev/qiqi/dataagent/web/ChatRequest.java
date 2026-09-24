package dev.qiqi.dataagent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 8_000) String query,
        @Size(max = 120) String conversationId,
        boolean online,
        PlanDecision plan) {
    public ChatRequest(String query, String conversationId, boolean online) {
        this(query, conversationId, online, null);
    }

    /** 计划确认。confirmed 为 false 时 feedback 是修改意见。 */
    public record PlanDecision(String toolCallId, boolean confirmed, @Size(max = 8_000) String feedback) {}
}
