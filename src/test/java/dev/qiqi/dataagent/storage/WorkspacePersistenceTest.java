package dev.qiqi.dataagent.storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.chart.*;
import dev.qiqi.dataagent.query.QueryResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class WorkspacePersistenceTest {
    @TempDir Path directory;
    LocalWorkspace workspace() { return new LocalWorkspace(new StorageProperties(directory), new ObjectMapper()); }
    @Test void queryAndChartSurviveNewStoreInstancesAndStayOwnerScoped() {
        var result = new QueryResult("query", List.of("x"), List.of(Map.of("x", 2)), 1, false, 4, "SELECT 2");
        new ChartQueryStore(workspace()).remember("1", "s", result);
        assertThat(new ChartQueryStore(workspace()).require("1", "s", "query")).isEqualTo(result);
        assertThatThrownBy(() -> new ChartQueryStore(workspace()).require("2", "s", "query")).isInstanceOf(IllegalArgumentException.class);
        var artifact = new ChartArtifact("id", "query", "Chart", "bar", "data:image/png;base64,AAAA");
        assertThat(new ChartArtifactStore(workspace()).save("1", artifact).source()).isEqualTo("/api/charts/id");
        assertThat(new ChartArtifactStore(workspace()).require("1", "id")).isEqualTo(artifact);
        assertThatThrownBy(() -> new ChartArtifactStore(workspace()).require("2", "id")).hasMessageContaining("404");
        assertThat(workspace().file("../user", "files", "../../file").normalize().startsWith(directory)).isTrue();
    }
    @Test void realAgentRestoresConversationFromDiskWithSeparateAgentInstances() {
        var context = RuntimeContext.builder().userId("1").sessionId(LocalWorkspace.key(".." )).build();
        runAgent(context, "remember ALPHA", false);
        runAgent(context, "what did I say", true);
        runAgent(RuntimeContext.builder().userId("2").sessionId(LocalWorkspace.key("..")).build(), "other user", false);
    }
    void runAgent(RuntimeContext context, String query, boolean expectMemory) {
        Model model = new Model() {
            public String getModelName() { return "persistence-script"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                long earlier = messages.stream().filter(m -> m.getContent().stream().anyMatch(b -> b instanceof TextBlock t && t.getText().equals("saved ALPHA"))).count();
                assertThat(earlier > 0).isEqualTo(expectMemory);
                return Flux.just(ChatResponse.builder().id("answer").finishReason("stop").content(List.of(TextBlock.builder().text("saved ALPHA").build())).build());
            }
        };
        ReActAgent.builder().name("persist-test").model(model).stateStore(new JsonFileAgentStateStore(workspace().stateDirectory()))
                .build().streamEvents(new UserMessage(query), context).blockLast(java.time.Duration.ofSeconds(10));
    }
    @Test void csvExportQuotesCellsAndNeutralizesTextFormulasWithoutChangingNumbers() {
        assertThat(CsvExport.cell("=HYPERLINK(\"x\")")).startsWith("\"'=");
        assertThat(CsvExport.cell("  +1\nignored")).startsWith("\"'  +");
        assertThat(CsvExport.cell(-42)).isEqualTo("\"-42\"");
        assertThat(CsvExport.cell("a,b\nc")).isEqualTo("\"a,b\nc\"");
    }
}
