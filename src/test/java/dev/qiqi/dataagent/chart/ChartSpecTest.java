package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.query.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ChartSpecTest {
    static QueryResult result(String id, boolean truncated, List<Map<String, Object>> rows) {
        return new QueryResult(id, List.of("month", "revenue"), rows, rows.size(), truncated, 1, "SELECT …");
    }
    static QueryResult sample() {
        return result("q1", false, List.of(Map.of("month", "January", "revenue", 120), Map.of("month", "February", "revenue", 160)));
    }
    static ChartSpec spec(String type) { return new ChartSpec("q1", type, "month", "revenue", "收入趋势"); }

    @ParameterizedTest @ValueSource(strings = {"bar", "line", "pie"})
    void completeDataKeepsQueryValues(String type) {
        Map<String, Object> option = spec(type).option(sample());
        var series = (Map<?, ?>) ((List<?>) option.get("series")).getFirst();
        assertThat(series.get("type")).isEqualTo(type);
        if (type.equals("pie")) assertThat(series.get("data")).isEqualTo(List.of(
                Map.of("name", "January", "value", 120), Map.of("name", "February", "value", 160)));
        else assertThat(series.get("data")).isEqualTo(List.of(120, 160));
    }
    @Test void rejectsTruncationMissingColumnsAndDuplicateCategories() {
        assertThatThrownBy(() -> spec("bar").option(result("q1", true, sample().rows()))).hasMessageContaining("截断");
        assertThatThrownBy(() -> new ChartSpec("q1", "bar", "fake", "revenue", null).option(sample())).hasMessageContaining("真实");
        assertThatThrownBy(() -> spec("bar").option(result("q1", false,
                List.of(sample().rows().getFirst(), sample().rows().getFirst())))).hasMessageContaining("重复");
    }
    @Test void rejectsBadValuesAndMisleadingPies() {
        for (Object value : List.of("120", Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThatThrownBy(() -> spec("bar").option(result("q1", false,
                    List.of(Map.of("month", "Jan", "revenue", value))))).hasMessageContaining("数值");
        }
        for (int value : List.of(-1, 0)) assertThatThrownBy(() -> spec("pie").option(result("q1", false,
                List.of(Map.of("month", "Jan", "revenue", value))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec("bar").option(result("q1", false, List.of()))).hasMessageContaining("1–100");
    }
    @Test void multiSeriesPreservesMissingPointsAndRejectsDuplicatePairs() {
        var rows = List.<Map<String, Object>>of(
                Map.of("month", "Jan", "department", "A", "revenue", 120),
                Map.of("month", "Feb", "department", "A", "revenue", 160),
                Map.of("month", "Feb", "department", "B", "revenue", 80));
        var query = new QueryResult("q1", List.of("month", "department", "revenue"), rows, 3, false, 1, "SELECT …");
        var chart = new ChartSpec("q1", "line", "month", "revenue", "趋势", "department");
        var series = (List<?>) chart.option(query).get("series");
        assertThat(((Map<?, ?>) series.get(1)).get("data")).isEqualTo(java.util.Arrays.asList(null, 80));
        var duplicate = new QueryResult("q1", query.columns(), List.of(rows.getFirst(), rows.getFirst()), 2, false, 1, "SELECT …");
        assertThatThrownBy(() -> chart.option(duplicate)).hasMessageContaining("重复");
    }
    @Test void horizontalRankingUsesNumericXAxisAndOriginalValues() {
        var option = spec("horizontal_bar").option(sample());
        assertThat(((Map<?, ?>) option.get("xAxis")).get("type")).isEqualTo("value");
        assertThat(((Map<?, ?>) ((List<?>) option.get("series")).getFirst()).get("data")).isEqualTo(List.of(120, 160));
    }
}
