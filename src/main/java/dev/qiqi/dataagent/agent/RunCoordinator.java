package dev.qiqi.dataagent.agent;

import dev.qiqi.dataagent.observability.RunTrace;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.HashMap;
import java.util.Map;

/** Single-node exclusion: a session cannot have two simultaneous writers. */
@Component
public class RunCoordinator {
    private final Map<String, Handle> active = new HashMap<>();
    public synchronized Handle start(RunTrace trace) {
        String slot = trace.owner() + ":" + trace.snapshot().conversationId();
        if (active.containsKey(slot)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话已有运行，请等待完成或先停止。");
        Handle handle = new Handle(slot, trace, new RunCancellation());
        active.put(slot, handle);
        return handle;
    }
    public synchronized void cancel(String id, long owner) {
        active.values().stream().filter(h -> h.trace.id().equals(id) && h.trace.owner() == owner).findFirst().ifPresent(handle -> {
            handle.trace.cancel("USER_CANCELLED"); handle.cancellation.cancel();
        });
    }
    public synchronized void release(Handle handle) { active.remove(handle.slot(), handle); }
    public record Handle(String slot, RunTrace trace, RunCancellation cancellation) {}
}
