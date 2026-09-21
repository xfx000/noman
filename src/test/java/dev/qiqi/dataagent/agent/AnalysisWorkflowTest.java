package dev.qiqi.dataagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.config.QiqiProperties;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.observability.ExecutionJournal;
import dev.qiqi.dataagent.storage.*;
import dev.qiqi.dataagent.tool.DataAgentToolkit;
import dev.qiqi.dataagent.web.AgentEventMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AnalysisWorkflowTest {
    @TempDir Path directory;
    @Autowired DataAgentToolkit toolkits;
    @Autowired IdentityService identities;
    @Autowired AnalysisSkills skills;
    @Autowired ObjectMapper json;
    @Autowired AgentEventMapper mapper;

    DataAgentFactory factory(int limit) {
        return new DataAgentFactory(new QiqiProperties(new QiqiProperties.Model("", "script", limit, "dashscope", ""), null, null),
                toolkits, new LocalWorkspace(new StorageProperties(directory), json), skills);
    }
    Model script(AtomicInteger calls) {
        return new Model() {
            public String getModelName() { return "offline-workflow-regression"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int step = calls.getAndIncrement();
                assertThat(tools.stream().map(ToolSchema::getName)).contains("todoWrite", "load_skill_through_path");
                String name; Map<String, Object> args;
                if (step == 0) {
                    name = "load_skill_through_path";
                    args = Map.of("skillId", skills.repository().getAllSkills().getFirst().getSkillId(), "path", "SKILL.md");
                } else if (step == 1 || step == 30) {
                    name = "todoWrite"; args = Map.of("todos", List.of(Map.of("content", "按多个指标核对数据", "status", step == 1 ? "in_progress" : "completed")));
                } else if (step == 2) { name = "list_tables"; args = Map.of(); }
                else if (step == 3) { name = "describe_table"; args = Map.of("table", "sales_order"); }
                else if (step < 30) {
                    name = step % 2 == 0 ? "validate_sql" : "execute_sql";
                    args = Map.of("sql", "SELECT COUNT(*) AS total FROM sales_order WHERE total_amount >= " + (step - 4) / 2);
                } else return Flux.just(ChatResponse.builder().id("final").finishReason("stop")
                        .content(List.of(TextBlock.builder().text("已核对指标，并保留查询证据。").build())).build());
                try { return Flux.just(ChatResponse.builder().id("reply-" + step).finishReason("tool_calls")
                        .content(List.of(ToolUseBlock.builder().id("call-" + step).name(name).input(args)
                                .content(json.writeValueAsString(args)).build())).build()); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
    }
    @Test void realAssemblyLoadsSkillUpdatesPlanAndFinishesWorkflowBeyondOldBudget() {
        var calls = new AtomicInteger();
        var agent = factory(40).create(identities.findActiveByUsername("admin").orElseThrow(), false, script(calls));
        var context = RuntimeContext.builder().userId("1").sessionId("workflow").build();
        var events = agent.streamEvents(new UserMessage("核对多个指标并交付报告"), context).collectList().block(Duration.ofSeconds(30));
        assertThat(events).noneMatch(ExceedMaxItersEvent.class::isInstance);
        assertThat(calls.get()).isGreaterThan(24).isLessThanOrEqualTo(40);
        var ends = events.stream().filter(ToolResultEndEvent.class::isInstance).map(ToolResultEndEvent.class::cast).toList();
        assertThat(ends).hasSize(31).allSatisfy(end -> assertThat(end.getState()).isEqualTo(ToolResultState.SUCCESS));
        var journal = new ExecutionJournal("r", "workflow");
        events.forEach(event -> journal.accept(mapper.map(event)));
        journal.finish("SUCCEEDED", null);
        assertThat(journal.snapshot().tools().getFirst().result()).contains("Qiqi 数据分析工作流", "todoWrite");
        assertThat(journal.snapshot().todos().getFirst().path("status").asText()).isEqualTo("completed");
        assertThat(journal.snapshot().evidence()).hasSize(13);
        assertThat(journal.snapshot().tools().stream().filter(tool -> tool.name().equals("execute_sql")))
                .allSatisfy(tool -> assertThat(tool.arguments()).contains("SELECT COUNT(*)"));
        assertThat(agent.getAgentState(context).getTasksContext().getTasks()).hasSize(1);
    }
    @Test void lowBudgetEmitsExplicitLimitAndPreservesUnfinishedPlan() {
        var agent = factory(3).create(identities.findActiveByUsername("admin").orElseThrow(), false, script(new AtomicInteger()));
        var events = agent.streamEvents(new UserMessage("分析数据"), RuntimeContext.builder().userId("1").sessionId("limited").build())
                .collectList().block(Duration.ofSeconds(20));
        assertThat(events).anyMatch(ExceedMaxItersEvent.class::isInstance);
        var journal = new ExecutionJournal("r", "limited");
        events.forEach(event -> journal.accept(mapper.map(event)));
        journal.finish("INCOMPLETE", "MAX_ITERATIONS");
        assertThat(journal.snapshot().progress()).contains("轮数上限");
        assertThat(journal.snapshot().todos().getFirst().path("status").asText()).isEqualTo("in_progress");
        assertThat(new QiqiProperties.Model("", "test", 1000, "dashscope", "").maxIterations()).isEqualTo(100);
    }
}
