package dev.qiqi.dataagent.web;

import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class ApplicationSmokeTest {
    @Autowired WebTestClient client;
    @Autowired dev.qiqi.dataagent.observability.RunTraceStore traces;

    @Test
    void metadataAndStaticPageWorkWithoutModelKey() {
        client.get().uri("/api/meta").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Qiqi DataAgent")
                .jsonPath("$.modelConfigured").isEqualTo(false)
                .jsonPath("$.demoUsers.length()").isEqualTo(3);
        byte[] page = client.get().uri("/").exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertThat(new String(page, java.nio.charset.StandardCharsets.UTF_8)).contains("Qiqi DataAgent");
    }

    @Test
    void unknownIdentityIsRejectedBeforeAgentExecution() {
        client.post().uri("/api/chat/stream").header("X-Qiqi-User", "unknown")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"hello\"}").exchange().expectStatus().isUnauthorized();
    }
    @Test void onlineRequiresConfigurationAndRunDetailsAreOwnerScoped() {
        client.post().uri("/api/chat/stream").header("X-Qiqi-User", "admin")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "search", "online", true)).exchange().expectStatus().isBadRequest();
        var trace = traces.start(1, "s", false);
        trace.fail("AGENT_FAILED");
        client.get().uri("/api/runs/" + trace.id()).header("X-Qiqi-User", "admin")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("FAILED");
        client.get().uri("/api/runs/" + trace.id()).header("X-Qiqi-User", "alice")
                .exchange().expectStatus().isNotFound();
    }

    @Test void streamFailureHasRunIdAndSafeErrorCode() {
        String stream = client.post().uri("/api/chat/stream").header("X-Qiqi-User", "admin")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "hello")).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(stream).contains("RUN_START", "RUN_END", "MODEL_NOT_CONFIGURED", "runId");
    }

    @Test void historyUsesRevisionChecksAndUploadsAreSessionScoped() {
        var saved = client.get().uri("/api/history").header("X-Qiqi-User", "alice").exchange().expectStatus().isOk()
                .expectBody(WorkspaceController.History.class).returnResult().getResponseBody();
        client.put().uri("/api/history").header("X-Qiqi-User", "alice").bodyValue(Map.of("revision", saved.revision(), "data", Map.of("chats", java.util.List.of())))
                .exchange().expectStatus().isOk();
        client.put().uri("/api/history").header("X-Qiqi-User", "alice").bodyValue(Map.of("revision", saved.revision(), "data", Map.of("chats", java.util.List.of())))
                .exchange().expectStatus().isEqualTo(409);
        var upload = client.post().uri("/api/files").header("X-Qiqi-User", "alice")
                .bodyValue(Map.of("conversationId", "file-session", "name", "sales.csv", "content", "area,amount\nNorth,12\nSouth,10"))
                .exchange().expectStatus().isOk().expectBody(com.fasterxml.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        String id = upload.path("preview").path("queryId").asText();
        client.get().uri("/api/evidence/" + id + "/csv?conversationId=file-session").header("X-Qiqi-User", "alice")
                .exchange().expectStatus().isOk().expectBody(String.class).value(text -> assertThat(text).contains("North", "12"));
        client.get().uri("/api/evidence/" + id + "?conversationId=file-session").header("X-Qiqi-User", "admin")
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/evidence/" + id + "?conversationId=other").header("X-Qiqi-User", "alice")
                .exchange().expectStatus().isBadRequest();
    }

}
