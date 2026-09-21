package dev.qiqi.dataagent.observability;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable run snapshots with a bounded one-hour memory cache. Single-node implementation. */
@Component
public class RunTraceStore {
    private dev.qiqi.dataagent.storage.LocalWorkspace workspace;
    public RunTraceStore() {}
    @org.springframework.beans.factory.annotation.Autowired
    public RunTraceStore(dev.qiqi.dataagent.storage.LocalWorkspace workspace) { this.workspace = workspace; }
    private final Map<String, RunTrace> traces = new LinkedHashMap<>();
    public synchronized RunTrace start(long owner, String conversationId, boolean online) {
        purge();
        if (traces.size() >= 200) {
            String finished = traces.values().stream().filter(trace -> !trace.running()).map(RunTrace::id).findFirst().orElse(null);
            if (finished == null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many active runs");
            traces.remove(finished);
        }
        RunTrace trace = new RunTrace(owner, conversationId, online);
        traces.put(trace.id(), trace);
        persist(trace);
        return trace;
    }
    public synchronized RunTrace.Snapshot require(String id, long owner) {
        purge();
        RunTrace trace = traces.get(id);
        if (trace == null && workspace != null) {
            var saved = workspace.read(Long.toString(owner), "runs", id, RunTrace.Snapshot.class).orElse(null);
            if (saved != null) {
                if (saved.status().equals("RUNNING")) return new RunTrace.Snapshot(saved.runId(), saved.conversationId(), saved.online(),
                        "INCOMPLETE", saved.startedAt(), saved.durationMs(), "SERVER_RESTART", saved.usage(), saved.operations());
                return saved;
            }
        }
        if (trace == null || trace.owner() != owner) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found or expired");
        return trace.snapshot();
    }
    public synchronized void persist(RunTrace trace) {
        if (workspace != null) workspace.write(Long.toString(trace.owner()), "runs", trace.id(), trace.snapshot());
    }
    private void purge() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        traces.values().removeIf(trace -> trace.startedAt().isBefore(cutoff) && !trace.running());
    }
}
