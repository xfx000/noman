package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.query.QueryResult;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable owner/session-scoped evidence with a small expiring memory cache. */
@Component
public class ChartQueryStore {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 200;
    private final Clock clock;
    private final LocalWorkspace workspace;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    public ChartQueryStore() { this(Clock.systemUTC()); }
    ChartQueryStore(Clock clock) { this.clock = clock; this.workspace = null; }
    @Autowired public ChartQueryStore(LocalWorkspace workspace) { this.clock = Clock.systemUTC(); this.workspace = workspace; }

    public synchronized void remember(String userId, String sessionId, QueryResult result) {
        purge();
        entries.put(new Key(userId, sessionId, result.queryId()), new Entry(result, clock.instant().plus(TTL)));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        if (workspace != null) {
            try { workspace.write(userId, "evidence", storageKey(sessionId, result.queryId()), result); }
            catch (RuntimeException ignored) { /* In-memory evidence still lets the current run chart. */ }
        }
    }

    public synchronized QueryResult require(String userId, String sessionId, String queryId) {
        purge();
        Entry entry = entries.get(new Key(userId, sessionId, queryId));
        if (entry == null && workspace != null) return workspace.read(userId, "evidence", storageKey(sessionId, queryId), QueryResult.class)
                .orElseThrow(() -> new IllegalArgumentException("查询证据不存在，请在当前会话重新执行查询。"));
        if (entry == null) throw new IllegalArgumentException("查询证据不存在或已过期，请在当前会话重新执行查询后画图。");
        return entry.result();
    }

    private static String storageKey(String session, String id) { return LocalWorkspace.key(session) + ":" + LocalWorkspace.key(id); }
    private void purge() { entries.values().removeIf(entry -> !entry.expiresAt().isAfter(clock.instant())); }
    private record Key(String userId, String sessionId, String queryId) {}
    private record Entry(QueryResult result, Instant expiresAt) {}
}
