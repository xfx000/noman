package dev.qiqi.dataagent.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LocalSettingsTest {
    @TempDir Path directory;
    LocalSettingsService service() {
        return new LocalSettingsService(new MockEnvironment()
                .withProperty("qiqi.model.api-key","test-existing-key")
                .withProperty("qiqi.model.provider","openai")
                .withProperty("qiqi.model.base-url","https://example.com/v1")
                .withProperty("qiqi.model.name","test-model")
                .withProperty("spring.datasource.url","jdbc:h2:mem:test"),directory.resolve("application-local.properties").toString());
    }
    @Test void secretsAreWriteOnlyAndBlankPreservesSavedKey() throws Exception {
        var settings=service();
        assertThat(settings.view().toString()).doesNotContain("test-existing-key");
        settings.saveModel(new LocalSettingsService.ModelInput("openai","https://example.com/v1","new-model","new-test-secret"));
        settings.saveModel(new LocalSettingsService.ModelInput("openai","https://example.com/v1","other-model",""));
        assertThat(settings.view().toString()).doesNotContain("new-test-secret");
        assertThat(settings.view().get("restartRequired")).isEqualTo(true);
        var file=directory.resolve("application-local.properties");
        assertThat(Files.readString(file)).contains("new-test-secret");
        assertThat(Files.getPosixFilePermissions(file)).hasSize(2);
        settings.saveDatabase(new LocalSettingsService.DatabaseInput(300,12));
        assertThat(Files.readString(file)).contains("new-test-secret");
        assertThat(((Map<?,?>)settings.view().get("database")).get("maxRows")).isEqualTo("300");
    }
    @Test void invalidInputDoesNotWriteConfig() {
        var settings=service();
        assertThatThrownBy(()->settings.saveDatabase(new LocalSettingsService.DatabaseInput(10000,0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings.saveModel(new LocalSettingsService.ModelInput("openai","https://user:password@example.com","model",""))).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("application-local.properties")).doesNotExist();
    }
    @Test void creativeOptInIsOffByDefaultAndTakesEffectWithoutRestart() {
        var settings = service();
        assertThat(settings.hyperframesEnabled()).isFalse();
        settings.saveCreative(new LocalSettingsService.CreativeInput(true));
        assertThat(settings.hyperframesEnabled()).isTrue();
        assertThat(((Map<?,?>) settings.view().get("creative")).get("hyperframesEnabled")).isEqualTo(true);
        assertThat(settings.view().get("restartRequired")).isEqualTo(false);
        settings.saveCreative(new LocalSettingsService.CreativeInput(false));
        assertThat(settings.hyperframesEnabled()).isFalse();
    }
    @Test void onlySameOriginLocalRequestsCanAccessSettings() {
        var controller=new LocalSettingsController(service());
        var local=new InetSocketAddress("127.0.0.1",1234);
        assertThat(controller.get(MockServerHttpRequest.get("http://localhost:8080/api/settings")
            .remoteAddress(local).header("X-Qiqi-Settings","1").header("Origin","http://localhost:8080").build()).block()).containsKey("model");
        assertThatThrownBy(()->controller.get(MockServerHttpRequest.get("http://localhost:8080/api/settings")
            .remoteAddress(local).header("X-Qiqi-Settings","1").header("Origin","https://evil.example").build())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->controller.get(MockServerHttpRequest.get("http://localhost:8080/api/settings")
            .remoteAddress(local).build())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->controller.get(MockServerHttpRequest.get("http://localhost:8080/api/settings")
            .remoteAddress(new InetSocketAddress("192.0.2.1",1234)).header("X-Qiqi-Settings","1").build())).isInstanceOf(SecurityException.class);
    }
}
