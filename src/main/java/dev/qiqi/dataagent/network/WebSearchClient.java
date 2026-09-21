package dev.qiqi.dataagent.network;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class WebSearchClient {
    private final WebSearchProperties properties;
    private final WebClient client;

    @Autowired
    public WebSearchClient(WebSearchProperties properties) {
        this(properties, WebClient.builder().baseUrl("https://api.tavily.com")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000).responseTimeout(properties.timeout())))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(512 * 1024)).build());
    }
    WebSearchClient(WebSearchProperties properties, WebClient client) {
        this.properties = properties;
        this.client = client;
    }
    public Mono<SearchResult> search(String query) {
        return Mono.defer(() -> {
            if (!properties.configured()) return Mono.error(new IllegalStateException("Web search is not configured"));
            return client.post().uri("/search").headers(headers -> headers.setBearerAuth(properties.apiKey()))
                    .bodyValue(Map.of("query", query, "search_depth", "basic", "topic", "general",
                            "max_results", 5, "include_answer", false, "include_raw_content", false,
                            "include_images", false, "auto_parameters", false))
                    .retrieve().bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new IllegalStateException("Empty search response")))
                    .map(WebSearchClient::parse).timeout(properties.timeout());
        });
    }
    static SearchResult parse(JsonNode json) {
        if (!json.path("results").isArray()) throw new IllegalStateException("Invalid search response");
        List<Source> sources = new ArrayList<>();
        for (JsonNode item : json.path("results")) {
            String url = item.path("url").asText("");
            if (!safeUrl(url)) continue;
            sources.add(new Source(trim(item.path("title").asText(""), 200), url,
                    trim(item.path("content").asText(""), 1600)));
            if (sources.size() == 5) break;
        }
        return new SearchResult("Tavily", Instant.now().toString(), List.copyOf(sources));
    }
    private static boolean safeUrl(String value) {
        if (value.length() > 2048) return false;
        try {
            URI uri = URI.create(value);
            return List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
    private static String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    public record Source(String title, String url, String snippet) {}
    public record SearchResult(String provider, String retrievedAt, List<Source> sources) {}
}
