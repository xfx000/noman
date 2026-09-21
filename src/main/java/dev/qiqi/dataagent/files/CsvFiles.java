package dev.qiqi.dataagent.files;

import dev.qiqi.dataagent.storage.LocalWorkspace;
import dev.qiqi.dataagent.query.QueryResult;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

@Component
public class CsvFiles {
    private final LocalWorkspace workspace;
    public CsvFiles(LocalWorkspace workspace) { this.workspace = workspace; }
    public Table upload(String owner, String session, String name, String content) {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".csv") || name.length() > 120)
            throw new IllegalArgumentException("目前支持 CSV 文件，文件名最长 120 字符。");
        if (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw new IllegalArgumentException("CSV 文件不能超过 2 MB。");
        List<List<String>> records = parse(content.startsWith("\uFEFF") ? content.substring(1) : content);
        if (records.size() < 2) throw new IllegalArgumentException("CSV 需要表头和至少一行数据。");
        List<String> columns = records.removeFirst().stream().map(String::trim).toList();
        if (columns.stream().anyMatch(c -> c.isBlank() || c.length() > 80) || new HashSet<>(columns).size() != columns.size())
            throw new IllegalArgumentException("列名必须非空、不重复且不超过 80 字符。");
        if (records.stream().anyMatch(row -> row.size() != columns.size())) throw new IllegalArgumentException("CSV 每行的列数必须与表头一致。");
        Table table = new Table(UUID.randomUUID().toString(), name, session, columns, List.copyOf(records));
        workspace.write(owner, "files", table.id(), table);
        return table;
    }
    public Table require(String owner, String session, String id) {
        return workspace.read(owner, "files", id, Table.class).filter(table -> table.session().equals(session))
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或不属于当前会话。"));
    }
    public QueryResult analyze(Table table, String operation, String valueColumn, String groupBy) {
        long start = System.nanoTime();
        if ("preview".equals(operation)) {
            List<Map<String,Object>> rows = table.rows().stream().limit(20).map(row -> {
                Map<String,Object> values = new LinkedHashMap<>();
                for (int i = 0; i < table.columns().size(); i++) values.put(table.columns().get(i), row.get(i));
                return values;
            }).toList();
            return result(table, operation, valueColumn, groupBy, table.columns(), rows, table.rows().size() > 20, start);
        }
        if (!Set.of("count", "sum", "avg", "min", "max").contains(operation)) throw new IllegalArgumentException("不支持的文件分析操作。");
        int value = valueColumn == null ? -1 : table.columns().indexOf(valueColumn);
        int group = groupBy == null || groupBy.isBlank() ? -1 : table.columns().indexOf(groupBy);
        if (!operation.equals("count") && value < 0) throw new IllegalArgumentException("请指定实际存在的数值列。");
        if (groupBy != null && !groupBy.isBlank() && group < 0) throw new IllegalArgumentException("分组列不存在。");
        Map<String, Stats> groups = new LinkedHashMap<>();
        for (List<String> row : table.rows()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            String key = group < 0 ? "全部" : row.get(group);
            Stats stats = groups.computeIfAbsent(key, ignored -> new Stats());
            if (groups.size() > 100) throw new IllegalArgumentException("分组超过 100 个，请换用更少类别的分组列。");
            stats.rows++;
            if (!operation.equals("count") && !row.get(value).isBlank()) stats.add(number(row.get(value)));
        }
        List<Map<String,Object>> rows = new ArrayList<>();
        groups.forEach((key, stats) -> {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("category", key);
            row.put("value", switch (operation) {
                case "count" -> stats.rows;
                case "sum" -> stats.sum;
                case "avg" -> stats.n == 0 ? null : stats.sum.divide(BigDecimal.valueOf(stats.n), MathContext.DECIMAL128);
                case "min" -> stats.min;
                default -> stats.max;
            }); rows.add(row);
        });
        return result(table, operation, valueColumn, groupBy, List.of("category", "value"), rows, false, start);
    }
    private static BigDecimal number(String value) {
        String text = value.trim();
        if (text.length() > 100 || !text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,2})?"))
            throw new IllegalArgumentException("数值列包含非数值内容，请先确认列类型。空单元格不参与数值统计。");
        return new BigDecimal(text);
    }
    private static QueryResult result(Table table, String operation, String valueColumn, String groupBy, List<String> columns, List<Map<String,Object>> rows, boolean truncated, long start) {
        return new QueryResult(UUID.randomUUID().toString(), columns, rows, rows.size(), truncated, (System.nanoTime() - start) / 1_000_000, "", Map.of("type", "csv", "fileId", table.id(), "name", table.name(),
                "operation", operation, "valueColumn", valueColumn == null ? "" : valueColumn, "groupBy", groupBy == null ? "" : groupBy, "totalRows", table.rows().size()));
    }
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\0') throw new IllegalArgumentException("CSV 不能包含空字节。");
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else { quoted = false; closed = true; } }
                else cell.append(c);
            } else if (c == '"' && cell.isEmpty() && !closed) quoted = true;
            else if (c == ',' || c == '\n' || c == '\r') {
                row.add(cell.toString()); cell.setLength(0); closed = false;
                if (row.size() > 50) throw new IllegalArgumentException("CSV 最多 50 列。");
                if (c != ',') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    rows.add(List.copyOf(row)); row.clear();
                    if (rows.size() > 5001) throw new IllegalArgumentException("CSV 最多 5000 行数据。");
                }
            } else {
                if (closed || c == '"') throw new IllegalArgumentException("CSV 引号格式不正确。");
                cell.append(c);
            }
            if (cell.length() > 10000) throw new IllegalArgumentException("CSV 单元格不能超过 10000 字符。");
        }
        if (quoted) throw new IllegalArgumentException("CSV 引号未闭合。");
        if (!row.isEmpty() || !cell.isEmpty() || closed) { row.add(cell.toString()); rows.add(List.copyOf(row)); }
        if (rows.size() > 5001 || rows.stream().anyMatch(r -> r.size() > 50)) throw new IllegalArgumentException("CSV 超过行列上限。");
        return rows;
    }
    private static class Stats {
        long rows, n; BigDecimal sum = BigDecimal.ZERO, min, max;
        void add(BigDecimal value) { n++; sum = sum.add(value); min = min == null ? value : min.min(value); max = max == null ? value : max.max(value); }
    }
    public record Table(String id, String name, String session, List<String> columns, List<List<String>> rows) {}
}
