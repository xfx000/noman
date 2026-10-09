package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.query.QueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a small, deterministic ECharts option from complete query results. */
public record ChartSpec(String queryId, String type, String categoryColumn, String valueColumn, String title, String seriesColumn) {
    public ChartSpec(String queryId, String type, String categoryColumn, String valueColumn, String title) {
        this(queryId, type, categoryColumn, valueColumn, title, null);
    }
    public ChartSpec {
        if (queryId == null || queryId.isBlank()) throw new IllegalArgumentException("queryId is required");
        if (type == null || !Set.of("bar", "line", "pie", "horizontal_bar").contains(type))
            throw new IllegalArgumentException("图表类型仅支持 bar、line、pie、horizontal_bar。");
        if (categoryColumn == null || valueColumn == null || categoryColumn.equals(valueColumn))
            throw new IllegalArgumentException("请分别指定分组列和数值列。");
        if (seriesColumn != null && (seriesColumn.isBlank() || seriesColumn.equals(categoryColumn) || seriesColumn.equals(valueColumn) || !type.equals("line")))
            throw new IllegalArgumentException("多系列仅用于折线图，请指定独立系列列。");
        title = title == null || title.isBlank() ? valueColumn + " · " + categoryColumn : title.trim();
        if (title.length() > 100) throw new IllegalArgumentException("图表标题不能超过 100 字。");
    }

    public Map<String, Object> option(QueryResult result) {
        if (result.truncated()) throw new IllegalArgumentException("查询结果已截断，请先聚合或缩小范围后再画图，不能把部分数据作为完整图表。");
        if (result.rows().isEmpty() || result.rows().size() > 100)
            throw new IllegalArgumentException("图表需要 1–100 行结果，请先聚合或缩小查询范围。");
        if (!result.columns().containsAll((seriesColumn == null ? List.of(categoryColumn, valueColumn) : List.of(categoryColumn, valueColumn, seriesColumn))))
            throw new IllegalArgumentException("图表列必须来自该 queryId 的真实查询结果。");
        List<String> categories = new ArrayList<>();
        List<Number> values = new ArrayList<>();
        Map<String, Map<String, Number>> groups = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : result.rows()) {
            Object category = row.get(categoryColumn), value = row.get(valueColumn);
            if (category == null || category.toString().length() > 100)
                throw new IllegalArgumentException("分组标签不能为空或超过 100 字。");
            String label = category.toString();
            Object group = seriesColumn == null ? "" : row.get(seriesColumn);
            if (seriesColumn != null && (group == null || group.toString().isBlank() || group.toString().length() > 100))
                throw new IllegalArgumentException("系列标签不能为空或超过 100 字。");
            if (!seen.add(label.length() + ":" + label + group)) throw new IllegalArgumentException("分组标签重复，请先用 SQL 聚合为每组一行。");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("数值列包含空值或非数值，请先修正查询，不能自动当作 0。");
            if (type.equals("pie") && number.doubleValue() < 0)
                throw new IllegalArgumentException("饼图不支持负数，请改用柱状图。");
            if (!categories.contains(label)) categories.add(label);
            values.add(number);
            groups.computeIfAbsent(group.toString(), ignored -> new LinkedHashMap<>()).put(label, number);
        }
        if (type.equals("pie") && values.stream().noneMatch(n -> n.doubleValue() > 0))
            throw new IllegalArgumentException("饼图至少需要一个正数。");
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("animation", false);
        option.put("backgroundColor", "#ffffff");
        option.put("color", List.of("#6578d7", "#8dcac0", "#d4aa65", "#ab8192", "#89a0e8"));
        option.put("textStyle", Map.of("fontFamily", "sans-serif", "color", "#626879"));
        option.put("title", Map.of("text", title, "left", "center", "top", 20));
        option.put("tooltip", Map.of("trigger", type.equals("pie") ? "item" : "axis"));
        if (type.equals("pie")) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < categories.size(); i++) data.add(Map.of("name", categories.get(i), "value", values.get(i)));
            option.put("series", List.of(Map.of("name", valueColumn, "type", "pie", "radius", List.of("30%", "65%"),
                    "center", List.of("50%", "55%"), "data", data)));
        } else {
            option.put("grid", Map.of("left", 65, "right", 35, "top", 85, "bottom", 80, "containLabel", true));
            var categoryAxis = Map.of("type", "category", "data", categories, "axisTick", Map.of("show", false), "axisLabel", Map.of("rotate", !type.equals("horizontal_bar") && categories.size() > 8 ? 35 : 0));
            var valueAxis = Map.of("type", "value", "name", valueColumn, "splitLine", Map.of("lineStyle", Map.of("color", "#edf1f5")));
            option.put("xAxis", type.equals("horizontal_bar") ? valueAxis : categoryAxis);
            option.put("yAxis", type.equals("horizontal_bar") ? categoryAxis : valueAxis);
            List<Map<String, Object>> series = new ArrayList<>();
            for (var group : groups.entrySet()) {
                List<Number> points = new ArrayList<>();
                for (String category : categories) points.add(group.getValue().get(category));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", seriesColumn == null ? valueColumn : group.getKey());
                item.put("type", type.equals("horizontal_bar") ? "bar" : type);
                item.put("data", points);
                item.put("barMaxWidth", 36);
                item.put("symbolSize", 7);
                item.put("lineStyle", Map.of("width", 3));
                series.add(item);
            }
            option.put("series", series);
            if (seriesColumn != null) option.put("legend", Map.of("type", "scroll", "top", 52));
            if (categories.size() > 12 && !type.equals("horizontal_bar"))
                option.put("dataZoom", List.of(Map.of("type", "inside"), Map.of("type", "slider", "bottom", 8, "height", 18)));

        }
        return option;
    }
}
