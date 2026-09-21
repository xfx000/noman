package dev.qiqi.dataagent.agent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Cancellation capability shared by the runtime and blocking query tools. */
public final class RunCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final Sinks.Empty<Void> stop = Sinks.empty();
    public boolean cancelled() { return cancelled.get(); }
    public Mono<Void> signal() { return stop.asMono(); }
    public void check() { if (cancelled()) throw new java.util.concurrent.CancellationException("Run cancelled"); }
    public AutoCloseable onCancel(Runnable action) {
        callbacks.add(action);
        if (cancelled()) action.run();
        return () -> callbacks.remove(action);
    }
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        for (Runnable action : callbacks) try { action.run(); } catch (RuntimeException ignored) { }
        stop.tryEmitEmpty();
    }
}
