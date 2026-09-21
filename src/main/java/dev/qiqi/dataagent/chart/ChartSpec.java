package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.query.QueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a small, deterministic ECharts option from complete query results. */
public record ChartSpec(String queryId, String type, String categoryColumn, String valueColumn, String title) {
    public ChartSpec {
        if (queryId == null || queryId.isBlank()) throw new IllegalArgumentException("queryId is required");
        if (type == null || !Set.of("bar", "line", "pie").contains(type))
            throw new IllegalArgumentException("图表类型仅支持 bar、line、pie。");
        if (categoryColumn == null || valueColumn == null || categoryColumn.equals(valueColumn))
            throw new IllegalArgumentException("请分别指定分组列和数值列。");
        title = title == null || title.isBlank() ? valueColumn + " · " + categoryColumn : title.trim();
        if (title.length() > 100) throw new IllegalArgumentException("图表标题不能超过 100 字。");
    }

    public Map<String, Object> option(QueryResult result) {
        if (result.truncated()) throw new IllegalArgumentException("查询结果已截断，请先聚合或缩小范围后再画图，不能把部分数据作为完整图表。");
        if (result.rows().isEmpty() || result.rows().size() > 100)
            throw new IllegalArgumentException("图表需要 1–100 行结果，请先聚合或缩小查询范围。");
        if (!result.columns().containsAll(List.of(categoryColumn, valueColumn)))
            throw new IllegalArgumentException("图表列必须来自该 queryId 的真实查询结果。");
        List<String> categories = new ArrayList<>();
        List<Number> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : result.rows()) {
            Object category = row.get(categoryColumn), value = row.get(valueColumn);
            if (category == null || category.toString().length() > 100)
                throw new IllegalArgumentException("分组标签不能为空或超过 100 字。");
            String label = category.toString();
            if (!seen.add(label)) throw new IllegalArgumentException("分组标签重复，请先用 SQL 聚合为每组一行。");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("数值列包含空值或非数值，请先修正查询，不能自动当作 0。");
            if (type.equals("pie") && number.doubleValue() < 0)
                throw new IllegalArgumentException("饼图不支持负数，请改用柱状图。");
            categories.add(label); values.add(number);
        }
        if (type.equals("pie") && values.stream().noneMatch(n -> n.doubleValue() > 0))
            throw new IllegalArgumentException("饼图至少需要一个正数。");
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("animation", false);
        option.put("backgroundColor", "#ffffff");
        option.put("color", List.of("#557968", "#8298b0", "#d4aa65", "#ab8192", "#9baf81"));
        option.put("title", Map.of("text", title, "left", "center", "top", 20));
        option.put("tooltip", Map.of("trigger", type.equals("pie") ? "item" : "axis"));
        if (type.equals("pie")) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < categories.size(); i++) data.add(Map.of("name", categories.get(i), "value", values.get(i)));
            option.put("series", List.of(Map.of("name", valueColumn, "type", "pie", "radius", List.of("30%", "65%"),
                    "center", List.of("50%", "55%"), "data", data)));
        } else {
            option.put("grid", Map.of("left", 65, "right", 35, "top", 85, "bottom", 80, "containLabel", true));
            option.put("xAxis", Map.of("type", "category", "data", categories, "axisLabel", Map.of("rotate", categories.size() > 8 ? 35 : 0)));
            option.put("yAxis", Map.of("type", "value", "name", valueColumn));
            option.put("series", List.of(Map.of("name", valueColumn, "type", type, "data", values)));
        }
        return option;
    }
}
