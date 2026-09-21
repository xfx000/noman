package dev.qiqi.dataagent.chart;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChartQueryStoreTest {
    @Test void evidenceIsBoundToUserSessionExpiryAndCapacity() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        ChartQueryStore store = new ChartQueryStore(clock);
        store.remember("alice", "one", ChartSpecTest.sample());
        assertThat(store.require("alice", "one", "q1")).isEqualTo(ChartSpecTest.sample());
        assertThatThrownBy(() -> store.require("bob", "one", "q1")).hasMessageContaining("不存在");
        assertThatThrownBy(() -> store.require("alice", "two", "q1")).hasMessageContaining("不存在");
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        assertThatThrownBy(() -> store.require("alice", "one", "q1")).hasMessageContaining("过期");
        for (int i = 0; i <= 200; i++) store.remember("alice", "one", ChartSpecTest.result("q" + i, false, ChartSpecTest.sample().rows()));
        assertThatThrownBy(() -> store.require("alice", "one", "q0")).hasMessageContaining("不存在");
        assertThat(store.require("alice", "one", "q200")).isNotNull();
    }
}
