package dev.qiqi.dataagent.chart;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("qiqi.chart")
public record ChartProperties(@DefaultValue("false") boolean enabled,
                              @DefaultValue("http://localhost:3033/mcp") URI mcpUrl,
                              @DefaultValue("30s") Duration timeout) {
    public ChartProperties {
        if (mcpUrl == null || !Set.of("http", "https").contains(mcpUrl.getScheme())
                || mcpUrl.getHost() == null || mcpUrl.getUserInfo() != null
                || mcpUrl.getQuery() != null || mcpUrl.getFragment() != null) {
            throw new IllegalArgumentException("qiqi.chart.mcp-url must be an HTTP(S) endpoint without credentials or query parameters");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("qiqi.chart.timeout must be greater than zero and at most 2m");
        }
    }
}
