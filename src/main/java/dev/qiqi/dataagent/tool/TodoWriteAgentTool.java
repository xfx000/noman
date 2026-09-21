package dev.qiqi.dataagent.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.builtin.TodoTools;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

/** Validated adapter around AgentScope's session-persisted task state. */
@Component
public class TodoWriteAgentTool implements AgentTool {
    public String getName() { return "todoWrite"; }
    public String getDescription() {
        return "Create/update the complete task plan for a multi-step analysis. Use before data exploration, "
                + "then update each step as work proceeds. At most one in_progress. Preserve unfinished tasks on failure. "
                + "Do not use for simple questions or mark unverified work completed.";
    }
    public Map<String, Object> getParameters() {
        var item = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("content", "status"), "properties", Map.of(
                        "content", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                        "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"))));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("todos"),
                "properties", Map.of("todos", Map.of("type", "array", "minItems", 1, "maxItems", 20, "items", item)));
    }

    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromSupplier(() -> {
            var ctx = param.getRuntimeContext();
            if (ctx == null || ctx.getUserId() == null || ctx.getSessionId() == null || ctx.getAgentState() == null)
                return ToolResultBlock.error("缺少当前会话任务状态，计划未更新。");
            if (!(param.getInput().get("todos") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 20)
                return ToolResultBlock.error("todos 必须包含 1–20 项任务。");
            List<TodoTools.TodoItem> items = new ArrayList<>();
            Set<String> contents = new HashSet<>();
            int active = 0;
            for (Object value : raw) {
                if (!(value instanceof Map<?, ?> item) || !(item.get("content") instanceof String content)
                        || content.isBlank() || content.length() > 200 || !contents.add(content.trim())
                        || !(item.get("status") instanceof String status)
                        || !Set.of("pending", "in_progress", "completed").contains(status))
                    return ToolResultBlock.error("任务内容应为不重复的 1–200 字文本，状态为 pending/in_progress/completed。");
                if (status.equals("in_progress")) active++;
                items.add(new TodoTools.TodoItem(content.trim(), status, null));
            }
            if (active > 1) return ToolResultBlock.error("最多只能有一项 in_progress 任务。");
            synchronized (ctx.getAgentState().getTasksContext()) {
                String result = new TodoTools().todoWrite(items, ctx.getAgentState());
                var todos = ctx.getAgentState().getTasksContext().getTasks().stream().map(task -> Map.of(
                        "id", task.getId(), "content", task.getSubject(), "status", task.getState().getWire())).toList();
                return ToolResultBlock.of(TextBlock.builder().text(result).build(), Map.of("todos", todos)).withState(io.agentscope.core.message.ToolResultState.SUCCESS);
            }
        });
    }
}
