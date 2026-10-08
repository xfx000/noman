package dev.qiqi.dataagent.agent;

import dev.qiqi.dataagent.identity.UserIdentity;
import dev.qiqi.dataagent.network.NetworkAccess;
import dev.qiqi.dataagent.plan.AnalysisMode;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class DataAgentService {
    private final DataAgentFactory factory;
    public DataAgentService(DataAgentFactory factory) { this.factory = factory; }
    public Flux<AgentEvent> stream(String query, String conversationId, UserIdentity identity, boolean online, RunCancellation cancellation) {
        return stream(new UserMessage(query), conversationId, identity, online, AnalysisMode.AUTO, cancellation);
    }

    public Flux<AgentEvent> stream(Msg message, String conversationId, UserIdentity identity, boolean online, RunCancellation cancellation) {
        return stream(message, conversationId, identity, online, AnalysisMode.AUTO, cancellation);
    }

    public Flux<AgentEvent> stream(Msg message, String conversationId, UserIdentity identity, boolean online, AnalysisMode mode, RunCancellation cancellation) {
        return stream(message, conversationId, identity, online, mode, false, cancellation);
    }

    public Flux<AgentEvent> stream(Msg message, String conversationId, UserIdentity identity, boolean online, AnalysisMode mode, boolean creative, RunCancellation cancellation) {
        return Flux.defer(() -> {
            cancellation.check();
            var agent = factory.create(identity, online, mode, creative);
            var context = RuntimeContext.builder().userId(Long.toString(identity.id()))
                    .sessionId(LocalWorkspace.key(conversationId) + (creative ? "-hyperframes" : ""))
                    .put(NetworkAccess.class, new NetworkAccess(online)).put(AnalysisMode.class, mode)
                    .put(RunCancellation.class, cancellation).build();
            cancellation.onCancel(() -> agent.interrupt(context));
            return agent.streamEvents(message, context).takeUntilOther(cancellation.signal());
        });
    }
    public boolean modelConfigured() { return factory.modelConfigured(); }
}
