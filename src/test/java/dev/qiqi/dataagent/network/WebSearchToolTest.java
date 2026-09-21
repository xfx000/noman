package dev.qiqi.dataagent.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.identity.UserIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSearchToolTest {
    final IdentityService identities = mock(IdentityService.class);
    final WebSearchClient client = mock(WebSearchClient.class);
    final WebSearchTool tool = new WebSearchTool(new WebSearchProperties(true, "test", Duration.ofSeconds(1)), identities, client, new ObjectMapper());
    WebSearchToolTest() { when(identities.findActiveById("2")).thenReturn(Optional.of(new UserIdentity(2L,"alice","Alice","DEPARTMENT",10L))); }
    RuntimeContext context(NetworkAccess access) { return RuntimeContext.builder().userId("2").sessionId("s").put(NetworkAccess.class, access).build(); }
    ToolCallParam param(RuntimeContext context, Map<String,Object> args) { return ToolCallParam.builder().runtimeContext(context).input(args).build(); }
    @Test void offMissingContextOrForgedPermissionNeverCallsProvider() {
        for (var ctx : List.of(RuntimeContext.empty(), context(new NetworkAccess(false)), RuntimeContext.builder().userId("2").sessionId("s").build())) {
            var result = tool.callAsync(param(ctx, Map.of("query", "public", "online", true))).block();
            assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        }
        var result = tool.callAsync(param(context(new NetworkAccess(false)), Map.of("query", "public"))).block();
        assertThat(result.getMetadata()).containsEntry("failureCode", "WEB_DENIED");
        verifyNoInteractions(client);
    }
    @Test void enforcesPerRequestBudgetAndDoesNotReuseGrantInNextTurn() {
        when(client.search("public")).thenReturn(Mono.just(new WebSearchClient.SearchResult("Tavily", "now", List.of())));
        var context = context(new NetworkAccess(true));
        for (int i = 0; i < 3; i++) assertThat(tool.callAsync(param(context, Map.of("query", "public"))).block().getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(tool.callAsync(param(context, Map.of("query", "public"))).block().getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(tool.callAsync(param(context(new NetworkAccess(false)), Map.of("query", "public"))).block().getState()).isEqualTo(ToolResultState.ERROR);
        verify(client, times(3)).search("public");
    }
    @Test void providerAuthFailureDoesNotLeakResponseBodyOrKey() {
        when(client.search(anyString())).thenReturn(Mono.error(WebClientResponseException.create(401, "secret-key", null, "private-provider-body".getBytes(), null)));
        var result = tool.callAsync(param(context(new NetworkAccess(true)), Map.of("query", "public"))).block();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(result.getMetadata()).containsEntry("failureCode", "WEB_AUTH");
        assertThat(result.toString()).doesNotContain("secret-key", "private-provider-body");
    }
}
