package dev.qiqi.dataagent.agent;
import dev.qiqi.dataagent.observability.*;
import dev.qiqi.dataagent.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
class RunCoordinatorTest {
    @TempDir Path directory;
    @Test void sameSessionConflictsCancellationIsOwnerScopedAndCancelsUpstream() {
        var store = new RunTraceStore(); var coordinator = new RunCoordinator();
        var trace = store.start(1, "s", false); var handle = coordinator.start(trace);
        assertThatThrownBy(() -> coordinator.start(store.start(1,"s",false))).hasMessageContaining("409");
        var other = coordinator.start(store.start(2, "s", false));
        AtomicBoolean upstreamCancelled = new AtomicBoolean(), jdbcCancelled = new AtomicBoolean();
        handle.cancellation().onCancel(() -> jdbcCancelled.set(true));
        StepVerifier.create(Flux.never().doOnCancel(() -> upstreamCancelled.set(true)).takeUntilOther(handle.cancellation().signal()))
                .then(() -> { coordinator.cancel(trace.id(), 2); assertThat(handle.cancellation().cancelled()).isFalse(); })
                .then(() -> coordinator.cancel(trace.id(), 1)).verifyComplete();
        assertThat(upstreamCancelled).isTrue(); assertThat(jdbcCancelled).isTrue();
        assertThat(other.cancellation().cancelled()).isFalse();
        coordinator.release(handle);
        assertThatCode(() -> coordinator.start(store.start(1,"s",false))).doesNotThrowAnyException();
    }
    @Test void restartedServerReportsUnfinishedRunAndCanReadFinishedDiagnostics() {
        var workspace = new LocalWorkspace(new StorageProperties(directory), new ObjectMapper());
        var original = new RunTraceStore(workspace);
        var unfinished = original.start(1, "s", false);
        var done = original.start(1, "s2", false); done.fail("AGENT_FAILED"); original.persist(done);
        var restarted = new RunTraceStore(workspace);
        assertThat(restarted.require(unfinished.id(), 1).errorCode()).isEqualTo("SERVER_RESTART");
        assertThat(restarted.require(done.id(), 1).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> restarted.require(done.id(), 2)).hasMessageContaining("404");
    }
}
