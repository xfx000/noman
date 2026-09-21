package dev.qiqi.dataagent.network;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("qiqi.web-search")
public record WebSearchProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue("") String apiKey,
                                  @DefaultValue("15s") Duration timeout) {
    public WebSearchProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("qiqi.web-search.timeout must be greater than zero and at most 60s");
    }
    public boolean configured() { return enabled && !apiKey.isBlank(); }
    @Override public String toString() { return "WebSearchProperties[enabled=" + enabled + ", apiKey=REDACTED, timeout=" + timeout + "]"; }
}
