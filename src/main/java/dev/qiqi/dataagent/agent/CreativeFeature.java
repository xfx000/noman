package dev.qiqi.dataagent.agent;

import dev.qiqi.dataagent.settings.LocalSettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Server-side gate: a local opt-in and an explicit invocation in this user turn. */
@Component
public class CreativeFeature {
    private final ObjectProvider<LocalSettingsService> localSettings;
    private final Environment environment;

    public CreativeFeature(ObjectProvider<LocalSettingsService> localSettings, Environment environment) {
        this.localSettings = localSettings;
        this.environment = environment;
    }

    public boolean enabled() {
        LocalSettingsService local = localSettings.getIfAvailable();
        return local != null ? local.hyperframesEnabled()
                : environment.getProperty("qiqi.creative.hyperframes.enabled", Boolean.class, false);
    }

    public boolean invoked(String query) {
        return query != null && query.trim().matches("(?s)^/hyperframes(?:\\s+.*)?$");
    }
}
