package dev.qiqi.dataagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import io.agentscope.core.message.ToolUseBlock;
import dev.qiqi.dataagent.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPlanGateTest {
    @TempDir Path dir;

    AnalysisPlan single() {
        return new AnalysisPlan(List.of("订单数"), List.of(), "2005", List.of("rental"), List.of(), List.of(),
                "", List.of(), List.of());
    }

    AnalysisPlan broad() {
        return new AnalysisPlan(List.of("订单数", "收入"), List.of("月份"), "2005", List.of("rental"),
                List.of("按付款计收入"), List.of("chart", "report"), "全面分析",
                List.of(new AnalysisPlan.Section("趋势", List.of("月度折线图"))),
                List.of(new AnalysisPlan.Deliverable("报告", "关键数字")));
    }

    @Test void recordRejectsBroadPlanUntilSubmitAndAllowsSameMetricsLater() {
        var gate = new AnalysisPlanGate(new LocalWorkspace(new StorageProperties(dir), new ObjectMapper()), new ObjectMapper());
        assertThat(AnalysisPlanReview.invalid(new AnalysisPlan(List.of(), List.of(), "", List.of("rental"), List.of(), List.of(), "", List.of(), List.of())))
                .isEqualTo(AnalysisPlanReview.NEED_METRICS);
        assertThat(AnalysisPlanReview.needsUserConfirmation(broad(), null)).isEqualTo(AnalysisPlanReview.NEED_SUBMIT);
        assertThat(AnalysisPlanReview.needsUserConfirmation(single(), null)).isNull();
        gate.accept("1", "s", broad());
        gate.clearRound("1", "s");
        assertThat(gate.isRoundOpen("1", "s")).isFalse();
        assertThat(AnalysisPlanReview.needsUserConfirmation(broad(), gate.confirmed("1", "s"))).isNull();
        var extra = new AnalysisPlan(List.of("订单数", "收入", "客户数"), List.of(), "2005", List.of("rental"),
                List.of(), List.of("report"), "", List.of(), List.of());
        assertThat(AnalysisPlanReview.needsUserConfirmation(extra, gate.confirmed("1", "s"))).isEqualTo(AnalysisPlanReview.NEED_SUBMIT);
    }

    @Test void missingToolContentIsFilledFromInput() {
        var call = ToolUseBlock.builder().id("c").name("submit_analysis_plan")
                .input(java.util.Map.of("title", "对比收入", "metrics", java.util.List.of("收入"))).build();
        var fixed = ToolArgumentContent.ensure(call);
        assertThat(fixed.getContent()).contains("对比收入");
        assertThat(ToolArgumentContent.ensure(fixed).getContent()).isEqualTo(fixed.getContent());
    }
}
