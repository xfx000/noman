package dev.qiqi.dataagent.query;

import dev.qiqi.dataagent.config.QiqiProperties;
import dev.qiqi.dataagent.identity.UserIdentity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReadOnlyQueryService {
    private final JdbcTemplate jdbc;
    private final SqlPolicy sqlPolicy;
    private final DataScopeGuard dataScopeGuard;
    private final QiqiProperties properties;

    public ReadOnlyQueryService(JdbcTemplate jdbc, SqlPolicy sqlPolicy,
                                DataScopeGuard dataScopeGuard, QiqiProperties properties) {
        this.jdbc = jdbc;
        this.sqlPolicy = sqlPolicy;
        this.dataScopeGuard = dataScopeGuard;
        this.properties = properties;
    }

    public QueryResult execute(String sql, UserIdentity identity, String conversationId) {
        return execute(sql, identity, conversationId, new dev.qiqi.dataagent.agent.RunCancellation());
    }

    public QueryResult execute(String sql, UserIdentity identity, String conversationId, dev.qiqi.dataagent.agent.RunCancellation cancellation) {
        cancellation.check();
        SqlValidation validation = sqlPolicy.validate(sql);
        if (!validation.valid()) throw new IllegalArgumentException(validation.reason());
        String scopedSql = dataScopeGuard.apply(validation.safeSql(), identity);
        String queryId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            QueryRows data = jdbc.execute((ConnectionCallback<QueryRows>) connection -> {
                boolean previousReadOnly = connection.isReadOnly();
                try {
                    connection.setReadOnly(true);
                    try (var statement = connection.prepareStatement(scopedSql)) {
                        cancellation.onCancel(() -> { try { statement.cancel(); } catch (java.sql.SQLException ignored) { } });
                        cancellation.check();
                        statement.setQueryTimeout(Math.toIntExact(Math.max(1, properties.query().timeout().toSeconds())));
                        statement.setMaxRows(properties.query().maxRows() + 1);
                        try (var rs = statement.executeQuery()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            List<String> columns = new ArrayList<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
                            List<Map<String, Object>> rows = new ArrayList<>();
                            while (!cancellation.cancelled() && rs.next() && rows.size() <= properties.query().maxRows()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), rs.getObject(i));
                                rows.add(row);
                            }
                            boolean truncated = rows.size() > properties.query().maxRows();
                            if (truncated) rows.remove(rows.size() - 1);
                            return new QueryRows(columns, rows, truncated);
                        }
                    }
                } finally {
                    connection.setReadOnly(previousReadOnly);
                }
            });
            cancellation.check();
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            jdbc.update("""
                    INSERT INTO query_audit(query_id, user_id, conversation_id, sql_text, success, duration_ms)
                    VALUES (?, ?, ?, ?, TRUE, ?)
                    """, queryId, identity.id(), conversationId, scopedSql, durationMs);
            return new QueryResult(queryId, data.columns, List.copyOf(data.rows), data.rows.size(),
                    data.truncated, durationMs, scopedSql);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            jdbc.update("""
                    INSERT INTO query_audit(query_id, user_id, conversation_id, sql_text, success, duration_ms, error_message)
                    VALUES (?, ?, ?, ?, FALSE, ?, ?)
                    """, queryId, identity.id(), conversationId, scopedSql, durationMs, compact(e.getMessage()));
            throw e;
        }
    }

    private static String compact(String value) {
        if (value == null) return "query failed";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record QueryRows(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {}
}
