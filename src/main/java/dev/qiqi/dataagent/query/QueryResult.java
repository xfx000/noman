package dev.qiqi.dataagent.query;

import java.util.List;
import java.util.Map;

public record QueryResult(String queryId, List<String> columns, List<Map<String, Object>> rows,
                          int rowCount, boolean truncated, long durationMs, String executedSql,
                          Map<String,Object> source) {
    public QueryResult { source = source == null ? Map.of() : Map.copyOf(source); }
    public QueryResult(String queryId, List<String> columns, List<Map<String,Object>> rows,
                       int rowCount, boolean truncated, long durationMs, String executedSql) {
        this(queryId, columns, rows, rowCount, truncated, durationMs, executedSql, Map.of());
    }
}
