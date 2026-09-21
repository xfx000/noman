package dev.qiqi.dataagent.agent;

import dev.qiqi.dataagent.identity.UserIdentity;
import dev.qiqi.dataagent.network.NetworkAccess;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class DataAgentService {
    private final DataAgentFactory factory;
    public DataAgentService(DataAgentFactory factory) { this.factory = factory; }
    public Flux<AgentEvent> stream(String query, String conversationId, UserIdentity identity, boolean online, RunCancellation cancellation) {
        return Flux.defer(() -> {
            cancellation.check();
            var agent = factory.create(identity, online);
            var context = RuntimeContext.builder().userId(Long.toString(identity.id()))
                    .sessionId(LocalWorkspace.key(conversationId))
                    .put(NetworkAccess.class, new NetworkAccess(online)).put(RunCancellation.class, cancellation).build();
            cancellation.onCancel(() -> agent.interrupt(context));
            return agent.streamEvents(new UserMessage(query), context).takeUntilOther(cancellation.signal());
        });
    }
    public boolean modelConfigured() { return factory.modelConfigured(); }
}
