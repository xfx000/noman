package dev.qiqi.dataagent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 按用户和会话记住本轮是否已交计划、上次确认的计划，以及正在等待确认的调用。 */
@Service
public class AnalysisPlanGate {
    public static final String BLOCKED_MESSAGE = "请先探查表结构并提交分析计划";
    static final String COLLECTION = "analysis-plans";

    private final LocalWorkspace workspace;
    private final ObjectMapper json;

    public AnalysisPlanGate(LocalWorkspace workspace, ObjectMapper json) {
        this.workspace = workspace;
        this.json = json;
    }

    public void clearRound(String userId, String sessionId) {
        if (blank(userId) || blank(sessionId)) return;
        State current = read(userId, sessionId);
        write(userId, sessionId, new State(false, current.confirmed, null));
    }

    public boolean isRoundOpen(String userId, String sessionId) {
        return !blank(userId) && !blank(sessionId) && read(userId, sessionId).roundOpen;
    }

    public AnalysisPlan confirmed(String userId, String sessionId) {
        if (blank(userId) || blank(sessionId)) return null;
        return read(userId, sessionId).confirmed;
    }

    public void accept(String userId, String sessionId, AnalysisPlan plan) {
        if (blank(userId) || blank(sessionId) || plan == null) return;
        write(userId, sessionId, new State(true, plan, null));
    }

    public void savePending(String userId, String sessionId, PendingCall call) {
        if (blank(userId) || blank(sessionId) || call == null) return;
        State current = read(userId, sessionId);
        write(userId, sessionId, new State(current.roundOpen, current.confirmed, call));
    }

    public PendingCall pending(String userId, String sessionId) {
        if (blank(userId) || blank(sessionId)) return null;
        return read(userId, sessionId).pending;
    }

    public void clearPending(String userId, String sessionId) {
        if (blank(userId) || blank(sessionId)) return;
        State current = read(userId, sessionId);
        write(userId, sessionId, new State(current.roundOpen, current.confirmed, null));
    }

    private State read(String userId, String sessionId) {
        return workspace.read(userId, COLLECTION, sessionId, State.class).orElse(State.closed());
    }

    private void write(String userId, String sessionId, State state) {
        workspace.write(userId, COLLECTION, sessionId, state);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingCall(String id, String name, Map<String, Object> input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record State(boolean roundOpen, AnalysisPlan confirmed, PendingCall pending) {
        static State closed() { return new State(false, null, null); }
    }
}
