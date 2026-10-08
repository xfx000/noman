package dev.qiqi.dataagent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.agent.CreativeFeature;
import dev.qiqi.dataagent.chart.ChartQueryStore;
import dev.qiqi.dataagent.observability.ExecutionJournal;
import dev.qiqi.dataagent.observability.RunTrace;
import dev.qiqi.dataagent.observability.RunTraceStore;
import dev.qiqi.dataagent.query.QueryResult;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportVideoServiceTest {
    private static final String RUN = "de6b3f33-d40d-4c2f-a5d6-993a372177d0";
    private static final String CONVERSATION = "conversation-1";
    private final CreativeFeature creative = mock(CreativeFeature.class);
    private final RunTraceStore runs = mock(RunTraceStore.class);
    private final ChartQueryStore evidence = mock(ChartQueryStore.class);
    private final LocalWorkspace workspace = mock(LocalWorkspace.class);
    private final ReportVideoService service = new ReportVideoService(creative, runs, evidence, workspace, "unused", "");

    @Test void disabledFeatureDoesNotInspectOrRenderReport() {
        assertThat(service.offer(1, RUN).available()).isFalse();
        verifyNoInteractions(runs, evidence, workspace);
    }

    @Test void completedReportOffersVideoOnlyFromOwnedSessionEvidence() throws Exception {
        when(creative.enabled()).thenReturn(true);
        when(runs.require(RUN, 1)).thenReturn(run());
        when(runs.execution(RUN, 1)).thenReturn(report("SUCCEEDED", true));
        when(workspace.read(eq("1"), eq("report-videos"), eq(RUN), eq(ReportVideoService.VideoArtifact.class)))
                .thenReturn(Optional.empty());
        when(evidence.require("1", LocalWorkspace.key(CONVERSATION), "chart-query"))
                .thenReturn(new QueryResult("chart-query", List.of("region", "revenue"),
                        List.of(Map.of("region", "North", "revenue", 7100)), 1, false, 5, "SELECT ..."));

        var offer = service.offer(1, RUN);
        assertThat(offer.available()).isTrue();
        assertThat(offer.queryId()).isEqualTo("chart-query");
        verify(evidence).require("1", LocalWorkspace.key(CONVERSATION), "chart-query");
        verify(workspace, never()).write(anyString(), anyString(), anyString(), any());
    }

    @Test void unfinishedReportNeverGetsVideoOffer() throws Exception {
        when(creative.enabled()).thenReturn(true);
        when(runs.require(RUN, 1)).thenReturn(run());
        when(runs.execution(RUN, 1)).thenReturn(report("INCOMPLETE", true));
        assertThat(service.offer(1, RUN).available()).isFalse();
        verifyNoInteractions(evidence);
    }

    @Test void monthlyComparisonUsesVerifiedGrowthAmountInsteadOfTheFirstNumericColumn() {
        QueryResult result = new QueryResult("query", List.of("department_name", "jan_2026_revenue", "feb_2026_revenue", "growth_amount", "growth_rate_pct"),
                List.of(Map.of("department_name", "North", "jan_2026_revenue", 7100, "feb_2026_revenue", 9100,
                        "growth_amount", 2000, "growth_rate_pct", 28.17)), 1, false, 5, "SELECT ...");
        assertThat(ReportVideoService.metricColumn(result)).isEqualTo("growth_amount");
    }

    @Test void offerPrefersTheFinalGrowthBreakdownOverAnEarlierSingleMonthQuery() throws Exception {
        when(creative.enabled()).thenReturn(true);
        when(runs.require(RUN, 1)).thenReturn(run());
        ObjectMapper json = new ObjectMapper();
        when(runs.execution(RUN, 1)).thenReturn(new ExecutionJournal.Snapshot(1, RUN, CONVERSATION, 1,
                "SUCCEEDED", null, "分析完成", "报告正文", List.of(), List.of(),
                List.of(json.readTree("{\"queryId\":\"jan\"}"), json.readTree("{\"queryId\":\"growth\"}")),
                List.of(), List.of(), false, List.of(), List.of(), "text:final", null));
        when(workspace.read(eq("1"), eq("report-videos"), eq(RUN), eq(ReportVideoService.VideoArtifact.class)))
                .thenReturn(Optional.empty());
        when(evidence.require("1", LocalWorkspace.key(CONVERSATION), "jan"))
                .thenReturn(new QueryResult("jan", List.of("department", "jan_revenue"),
                        List.of(Map.of("department", "North", "jan_revenue", 7100)), 1, false, 5, "SELECT ..."));
        when(evidence.require("1", LocalWorkspace.key(CONVERSATION), "growth"))
                .thenReturn(new QueryResult("growth", List.of("department", "jan_revenue", "growth_amount"),
                        List.of(Map.of("department", "North", "jan_revenue", 7100, "growth_amount", 2000),
                                Map.of("department", "South", "jan_revenue", 8300, "growth_amount", 1000)),
                        2, false, 5, "SELECT ..."));

        assertThat(service.offer(1, RUN).queryId()).isEqualTo("growth");
    }

    private RunTrace.Snapshot run() {
        return new RunTrace.Snapshot(RUN, CONVERSATION, false, "SUCCEEDED", "2026-10-08T00:00:00Z",
                1000, null, null, List.of());
    }
    private ExecutionJournal.Snapshot report(String status, boolean withEvidence) throws Exception {
        ObjectMapper json = new ObjectMapper();
        return new ExecutionJournal.Snapshot(1, RUN, CONVERSATION, 1, status, null, "分析完成", "报告正文",
                List.of(), List.of(), withEvidence ? List.of(json.readTree("{\"queryId\":\"chart-query\"}")) : List.of(),
                withEvidence ? List.of(json.readTree("{\"id\":\"chart-1\",\"queryId\":\"chart-query\"}")) : List.of(),
                List.of(), false, List.of(), List.of(), "text:final", null);
    }
}
