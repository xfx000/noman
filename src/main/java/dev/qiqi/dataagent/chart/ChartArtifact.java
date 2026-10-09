package dev.qiqi.dataagent.chart;

import java.util.Map;

public record ChartArtifact(String id, String queryId, String title, String type, String source, Map<String, Object> option) {
    public ChartArtifact(String id, String queryId, String title, String type, String source) {
        this(id, queryId, title, type, source, null);
    }
}
