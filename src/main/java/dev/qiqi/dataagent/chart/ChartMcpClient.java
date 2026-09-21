package dev.qiqi.dataagent.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Each chart owns its MCP session; errors/cancellation close it and the next call can reconnect. */
@Component
public class ChartMcpClient {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'\\)\\]]+");
    private static final int MAX_IMAGE_BYTES = 1_500_000;
    private final ChartProperties properties;
    private final ObjectMapper mapper;

    public ChartMcpClient(ChartProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Mono<ChartArtifact> render(ChartSpec spec, Map<String, Object> option) {
        return Mono.defer(() -> {
            if (!properties.enabled()) return Mono.error(new IllegalStateException("图表功能未启用。"));
            String json;
            try { json = mapper.writeValueAsString(option); }
            catch (Exception e) { return Mono.error(new IllegalArgumentException("图表数据无法编码。", e)); }
            return Mono.usingWhen(
                    McpClientBuilder.create("qiqi-charts")
                            .streamableHttpTransport(properties.mcpUrl().toString())
                            .customizeStreamableHttpClient(client -> client.connectTimeout(Duration.ofSeconds(5)))
                            .initializationTimeout(Duration.ofSeconds(5))
                            .timeout(properties.timeout()).buildAsync(),
                    client -> client.initialize().then(Mono.defer(() -> {
                        if (client.getCachedTool("generate_echarts") == null)
                            return Mono.error(new IllegalStateException("图表 MCP 缺少 generate_echarts 工具，请使用 mcp-echarts 0.7.1。"));
                        return client.callTool("generate_echarts", Map.of("echartsOption", json,
                                        "width", 1000, "height", 600, "outputType", "png"))
                                .map(result -> artifact(spec, result));
                    })).timeout(properties.timeout()),
                    this::close);
        });
    }

    private Mono<Void> close(McpClientWrapper client) {
        return Mono.<Void>fromRunnable(client::close).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ignored -> Mono.empty());
    }

    static ChartArtifact artifact(ChartSpec spec, McpSchema.CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException("图表 MCP 生成失败，请调整查询或图表类型后重试。");
        if (result.content() != null) for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.ImageContent image && "image/png".equals(image.mimeType())) {
                if (image.data() == null || image.data().length() > MAX_IMAGE_BYTES * 4 / 3)
                    throw new IllegalArgumentException("图表图片过大，请减少分组数量。");
                byte[] bytes = Base64.getDecoder().decode(image.data());
                if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
                        || bytes[4] != 13 || bytes[5] != 10 || bytes[6] != 26 || bytes[7] != 10)
                    throw new IllegalArgumentException("图表 MCP 返回了无效的 PNG 图片。");
                return make(spec, "data:image/png;base64," + image.data());
            }
            if (content instanceof McpSchema.TextContent text) {
                var match = URL.matcher(text.text());
                if (match.find()) {
                    String source = match.group();
                    URI uri = URI.create(source);
                    if (uri.getHost() != null && uri.getUserInfo() == null && source.length() <= 8192)
                        return make(spec, source);
                }
            }
        }
        throw new IllegalArgumentException("图表 MCP 没有返回可展示的 PNG 或图片链接。");
    }

    private static ChartArtifact make(ChartSpec spec, String source) {
        return new ChartArtifact(UUID.randomUUID().toString(), spec.queryId(), spec.title(), spec.type(), source);
    }
}
