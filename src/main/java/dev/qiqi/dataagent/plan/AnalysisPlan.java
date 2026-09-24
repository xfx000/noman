package dev.qiqi.dataagent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 分析计划。门控字段决定能不能直接执行；章节和最终输出只给人看。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisPlan(
        List<String> metrics,
        List<String> dimensions,
        String timeRange,
        List<String> tables,
        List<String> assumptions,
        List<String> outputs,
        String title,
        List<Section> sections,
        List<Deliverable> deliverables) {

    public static AnalysisPlan fromInput(Map<String, Object> input) {
        Map<String, Object> source = input == null ? Map.of() : input;
        return new AnalysisPlan(
                strings(source.get("metrics")),
                strings(source.get("dimensions")),
                text(source.get("timeRange")),
                strings(source.get("tables")),
                strings(source.get("assumptions")),
                strings(source.get("outputs")),
                text(source.get("title")),
                sections(source.get("sections")),
                deliverables(source.get("deliverables")));
    }

    public AnalysisPlan {
        metrics = clean(metrics);
        dimensions = clean(dimensions);
        timeRange = timeRange == null ? "" : timeRange.trim();
        tables = clean(tables);
        assumptions = clean(assumptions);
        outputs = clean(outputs);
        title = title == null ? "" : title.trim();
        sections = sections == null ? List.of() : List.copyOf(sections);
        deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
    }

    public record Section(String title, List<String> items) {
        public Section {
            title = title == null ? "" : title.trim();
            items = clean(items);
        }
    }

    public record Deliverable(String title, String detail) {
        public Deliverable {
            title = title == null ? "" : title.trim();
            detail = detail == null ? "" : detail.trim();
        }
    }

    static List<Section> sections(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Section> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            out.add(new Section(text(map.get("title")), strings(map.get("items"))));
        }
        return List.copyOf(out);
    }

    static List<Deliverable> deliverables(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Deliverable> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            out.add(new Deliverable(text(map.get("title")), text(map.get("detail"))));
        }
        return List.copyOf(out);
    }

    static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(text(item));
        return clean(out);
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    private static List<String> clean(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
