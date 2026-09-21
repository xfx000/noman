package dev.qiqi.dataagent.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataAgentPromptTest {
    @Test void analysisPromptRequiresPlanForbidsMidLoopQuestionsAndAutoCharts() {
        assertThat(DataAgentFactory.BASE_PROMPT)
                .contains("Task management is mandatory")
                .contains("todoWrite")
                .contains("Simplified Chinese")
                .contains("user-facing business update")
                .contains("after the analysis evidence has been verified")
                .contains("selected queryId")
                .contains("Begin the final report with a 核心结论 section")
                .doesNotContain("brief reasoning")
                .doesNotContain("call generate_chart immediately")
                .contains("Never ask the user whether to draw a chart")
                .doesNotContain("Simple questions do not need a plan")
                .doesNotContain("Give brief user-facing progress summaries")
                .doesNotContain("Activate charts for chart requests");
    }
}
