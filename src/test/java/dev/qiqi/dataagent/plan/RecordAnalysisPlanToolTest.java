package dev.qiqi.dataagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import dev.qiqi.dataagent.storage.StorageProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordAnalysisPlanToolTest {
    @TempDir Path dir;

    @Test void modeControlsConfirmationButDoesNotSkipPlanGate() {
        var json = new ObjectMapper();
        var gate = new AnalysisPlanGate(new LocalWorkspace(new StorageProperties(dir), json), json);
        var tool = new RecordAnalysisPlanTool(gate);
        var broad = Map.<String, Object>of("metrics", List.of("订单数", "收入"), "tables", List.of("sales_order"),
                "outputs", List.of("report"));
        var simple = Map.<String, Object>of("metrics", List.of("订单数"), "tables", List.of("sales_order"));
        assertThat(call(tool, "auto", AnalysisMode.AUTO, broad)).isEqualTo(ToolResultState.ERROR);
        assertThat(gate.isRoundOpen("1", "auto")).isFalse();
        assertThat(call(tool, "fast", AnalysisMode.FAST, broad)).isNotEqualTo(ToolResultState.ERROR);
        assertThat(gate.isRoundOpen("1", "fast")).isTrue();
        assertThat(call(tool, "review", AnalysisMode.REVIEW, simple)).isEqualTo(ToolResultState.ERROR);
        assertThat(gate.isRoundOpen("1", "review")).isFalse();
        assertThat(call(tool, "check", AnalysisMode.FAST, Map.of("metrics", List.of("已付款订单数"),
                "tables", List.of("sales_order"), "assumptions", List.of("需查询实际状态值"))))
                .isNotEqualTo(ToolResultState.ERROR);
        assertThat(gate.isRoundOpen("1", "check")).isTrue();
    }

    private ToolResultState call(RecordAnalysisPlanTool tool, String session, AnalysisMode mode, Map<String, Object> input) {
        return tool.callAsync(ToolCallParam.builder().input(input)
                .runtimeContext(RuntimeContext.builder().userId("1").sessionId(session)
                        .put(AnalysisMode.class, mode).build()).build()).block().getState();
    }
}
