package dev.qiqi.dataagent.agent;

import dev.qiqi.dataagent.settings.LocalSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreativeFeatureTest {
    @Test void requiresAnExplicitCommandAtTheStartOfThisMessage() {
        @SuppressWarnings("unchecked") ObjectProvider<LocalSettingsService> provider = mock(ObjectProvider.class);
        var feature = new CreativeFeature(provider, new MockEnvironment());
        assertThat(feature.enabled()).isFalse();
        assertThat(feature.invoked("/hyperframes 做一个十秒片头")).isTrue();
        assertThat(feature.invoked("  /hyperframes  ")).isTrue();
        assertThat(feature.invoked("帮我看看 /hyperframes 功能")).isFalse();
        assertThat(feature.invoked("/hyperframes-example")).isFalse();
        var local = mock(LocalSettingsService.class);
        when(provider.getIfAvailable()).thenReturn(local);
        when(local.hyperframesEnabled()).thenReturn(true);
        assertThat(feature.enabled()).isTrue();
    }
    @Test void creativeSkillIsAbsentFromTheNormalSkillRepository() throws Exception {
        var skills = new AnalysisSkills();
        try {
            assertThat(skills.repository().getAllSkills()).extracting(skill -> skill.getSkillId())
                    .hasSize(1)
                    .allSatisfy(id -> assertThat(id).contains("data-analysis").doesNotContain("hyperframes", "report-video"));
            assertThat(skills.creativeRepository().getAllSkills()).extracting(skill -> skill.getSkillId())
                    .hasSize(2)
                    .anySatisfy(id -> assertThat(id).contains("hyperframes"))
                    .anySatisfy(id -> assertThat(id).contains("report-video"))
                    .allSatisfy(id -> assertThat(id).doesNotContain("data-analysis"));
        } finally { skills.close(); }
    }
}
