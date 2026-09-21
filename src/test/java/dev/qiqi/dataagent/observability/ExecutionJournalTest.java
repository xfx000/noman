package dev.qiqi.dataagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.*;
import dev.qiqi.dataagent.web.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionJournalTest {
    @TempDir Path directory;
    static StreamEvent event(String type, String id, String name, String delta) {
        return new StreamEvent(type, Map.of("toolCallId", id, "toolCallName", name, "delta", delta));
    }
    @Test void fragmentsParallelCallsAndDuplicateEndsHaveStableSafeResults() {
        var journal = new ExecutionJournal("run", "session");
        journal.accept(event("TOOL_CALL_START", "a", "execute_sql", ""));
        journal.accept(event("TOOL_CALL_DELTA", "a", "execute_sql", "{\"sql\":\"SELECT 'private'\","));
        journal.accept(event("TOOL_CALL_START", "b", "web_search", ""));
        journal.accept(event("TOOL_CALL_DELTA", "b", "web_search", "{\"query\":\"public\",\"apiKey\":\"top-secret\"}"));
        assertThat(journal.snapshot().tools()).allSatisfy(tool -> assertThat(tool.arguments()).isEmpty());
        journal.accept(event("TOOL_CALL_DELTA", "a", "execute_sql", "\"token\":\"bearer-secret\"}"));
        journal.accept(event("TOOL_CALL_END", "a", "execute_sql", ""));
        journal.accept(event("TOOL_RESULT_START", "b", "web_search", ""));
        journal.accept(event("TOOL_RESULT_TEXT_DELTA", "a", "execute_sql", "{\"queryId\":\"q1\",\"columns\":[\"count\"],\"rows\":[{\"phone\":\"13912345678\"}],\"rowCount\":1}"));
        var end = new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "a", "state", "success"));
        journal.accept(end); journal.accept(end);
        var snapshot = journal.snapshot();
        assertThat(snapshot.tools()).hasSize(2);
        assertThat(snapshot.tools().getFirst().name()).isEqualTo("execute_sql");
        assertThat(snapshot.tools().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(snapshot.tools().getFirst().arguments()).contains("SELECT", "redacted").doesNotContain("private", "bearer-secret");
        assertThat(snapshot.toString()).doesNotContain("13912345678", "top-secret");
        assertThat(snapshot.evidence()).hasSize(1);
        journal.finish("CANCELLED", "USER_CANCELLED");
        assertThat(journal.snapshot().tools().get(1).status()).isEqualTo("CANCELLED");
        assertThat(journal.snapshot().tools().getFirst().status()).isEqualTo("SUCCEEDED");
    }
    @Test void missingIdsMalformedJsonAndOversizedPayloadDoNotLeakOrMerge() {
        var journal = new ExecutionJournal("r", "s");
        journal.accept(new StreamEvent("TOOL_RESULT_START", Map.of("toolCallName", "bad")));
        journal.accept(event("TOOL_CALL_DELTA", "malformed", "execute_sql", "{\"token\":\"" + "secret".repeat(20000)));
        journal.accept(event("TOOL_CALL_END", "malformed", "execute_sql", ""));
        journal.accept(event("TOOL_RESULT_TEXT_DELTA", "malformed", "execute_sql", "{\"apiKey\":\"half-secret"));
        journal.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "malformed", "state", "error")));
        assertThat(journal.snapshot().tools()).hasSize(1);
        assertThat(journal.snapshot().toString()).doesNotContain("secret").contains("不完整");
        assertThat(DisplaySanitizer.structured("{} trailing password=secret", false)).contains("不完整").doesNotContain("secret");
    }
    @Test void persistedJournalRestoresPlanEvidenceAndChartsAndRejectsOtherOwners() {
        var workspace = new LocalWorkspace(new StorageProperties(directory), new ObjectMapper());
        var store = new RunTraceStore(workspace);
        var trace = store.start(1, "session", false);
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "todo", "toolCallName", "todoWrite", "state", "success",
                "metadata", Map.of("todos", List.of(Map.of("id", "t", "content", "生成图表", "status", "in_progress"))))));
        trace.accept(new StreamEvent("TOOL_RESULT_END", Map.of("toolCallId", "chart", "toolCallName", "generate_chart", "state", "success",
                "metadata", Map.of("chart", Map.of("id", "c", "queryId", "q", "source", "/api/charts/c"),
                        "evidence", Map.of("queryId", "q", "columns", List.of("value"), "rowCount", 1)))));
        trace.accept(event("TOOL_RESULT_START", "pending", "execute_sql", ""));
        store.persist(trace);
        var restarted = new RunTraceStore(workspace);
        var restored = restarted.execution(trace.id(), 1);
        assertThat(restored.status()).isEqualTo("INCOMPLETE");
        assertThat(restored.errorCode()).isEqualTo("SERVER_RESTART");
        assertThat(restored.tools().getLast().status()).isEqualTo("INCOMPLETE");
        assertThat(restored.todos().getFirst().path("status").asText()).isEqualTo("in_progress");
        assertThat(restored.charts()).hasSize(1); assertThat(restored.evidence()).hasSize(1);
        assertThatThrownBy(() -> restarted.execution(trace.id(), 2)).hasMessageContaining("404");
        assertThatThrownBy(() -> store.execution(trace.id(), 2)).hasMessageContaining("404");
    }
}
