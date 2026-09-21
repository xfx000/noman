package dev.qiqi.dataagent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 8_000) String query,
        @Size(max = 120) String conversationId,
        boolean online) {}
