package dev.qiqi.dataagent.network;

import java.util.concurrent.atomic.AtomicInteger;

/** Trusted per-request capability, never a tool argument or persisted conversation state. */
public final class NetworkAccess {
    private final boolean allowed;
    private final AtomicInteger calls = new AtomicInteger();
    public NetworkAccess(boolean allowed) { this.allowed = allowed; }
    public boolean acquire() { return allowed && calls.getAndIncrement() < 3; }
}
