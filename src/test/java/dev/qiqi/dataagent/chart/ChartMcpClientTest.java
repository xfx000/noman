package dev.qiqi.dataagent.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChartMcpClientTest {
    // A real 1x1 PNG; malformed binary and active-content data URLs must never be accepted.
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
    @Test void acceptsPngAndMinioUrlAndRejectsErrors() {
        var image = new McpSchema.ImageContent(null, PNG, "image/png");
        var png = ChartMcpClient.artifact(ChartSpecTest.spec("bar"), new McpSchema.CallToolResult(List.of(image), false));
        assertThat(png.source()).isEqualTo("data:image/png;base64," + PNG);
        var url = new McpSchema.TextContent("https://storage.example/chart.png?token=signed");
        assertThat(ChartMcpClient.artifact(ChartSpecTest.spec("line"), new McpSchema.CallToolResult(List.of(url), false)).source())
                .isEqualTo(url.text());
        assertThatThrownBy(() -> ChartMcpClient.artifact(ChartSpecTest.spec("bar"), new McpSchema.CallToolResult(List.of(url), true)))
                .hasMessageContaining("失败");
        for (String bad : List.of("javascript:alert(1)", "data:image/svg+xml,<svg/>", "https://user:password@example.com/chart.png"))
            assertThatThrownBy(() -> ChartMcpClient.artifact(ChartSpecTest.spec("bar"),
                    new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(bad)), false))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledClientDoesNotConnect() {
        var client = new ChartMcpClient(new ChartProperties(false, URI.create("http://localhost:1/mcp"), Duration.ofSeconds(1)), new ObjectMapper());
        StepVerifier.create(client.render(ChartSpecTest.spec("bar"), ChartSpecTest.spec("bar").option(ChartSpecTest.sample())))
                .expectErrorMessage("图表功能未启用。").verify();
    }
}
