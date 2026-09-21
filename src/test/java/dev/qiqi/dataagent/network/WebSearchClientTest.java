package dev.qiqi.dataagent.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class WebSearchClientTest {
    @Test void requestUsesBearerAndNormalizesUntrustedSources() {
        var properties = new WebSearchProperties(true, "test-secret", Duration.ofSeconds(1));
        var client = new WebSearchClient(properties, WebClient.builder().baseUrl("https://api.tavily.com").exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("https://api.tavily.com/search");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer test-secret");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("""
                    {"results":[{"title":"Source","url":"https://example.com/report","content":"Public summary"},
                    {"url":"javascript:alert(1)"},{"url":"https://user:pass@example.com/x"}],"answer":"Do not trust this"}
                    """).build());
        }).build());
        var result = client.search("public market report").block();
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().getFirst().snippet()).isEqualTo("Public summary");
        assertThat(result.retrievedAt()).isNotBlank();
        assertThat(properties.toString()).doesNotContain("test-secret");
    }
    @Test void disabledSearchNeverMakesAnHttpRequest() {
        AtomicInteger calls = new AtomicInteger();
        var client = new WebSearchClient(new WebSearchProperties(false, "", Duration.ofSeconds(1)),
                WebClient.builder().exchangeFunction(request -> { calls.incrementAndGet(); return Mono.never(); }).build());
        StepVerifier.create(client.search("test")).expectError(IllegalStateException.class).verify();
        assertThat(calls).hasValue(0);
    }
    @Test void timesOutWithoutRetrying() {
        AtomicInteger calls = new AtomicInteger();
        var client = new WebSearchClient(new WebSearchProperties(true, "test", Duration.ofMillis(30)),
                WebClient.builder().baseUrl("https://api.tavily.com").exchangeFunction(request -> { calls.incrementAndGet(); return Mono.never(); }).build());
        StepVerifier.create(client.search("test")).expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(3));
        assertThat(calls).hasValue(1);
    }
    @Test void malformedResponseIsAnErrorAndSourceLengthsAreBounded() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThatThrownBy(() -> WebSearchClient.parse(json.readTree("{}"))).isInstanceOf(IllegalStateException.class);
        var data = json.createObjectNode();
        var results = data.putArray("results");
        for (int i = 0; i < 10; i++) results.addObject().put("url", "https://example.com/" + i).put("content", "x".repeat(2000));
        assertThat(WebSearchClient.parse(data).sources()).hasSize(5).allSatisfy(source -> assertThat(source.snippet()).hasSize(1600));
    }
}
