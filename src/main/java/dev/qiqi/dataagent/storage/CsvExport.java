package dev.qiqi.dataagent.storage;

import dev.qiqi.dataagent.query.QueryResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public final class CsvExport {
    private CsvExport() {}
    public static byte[] bytes(QueryResult result) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(line(result.columns())).append("\r\n");
        for (var row : result.rows()) csv.append(line(result.columns().stream().map(row::get).toList())).append("\r\n");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String line(List<?> values) { return values.stream().map(CsvExport::cell).collect(Collectors.joining(",")); }
    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        // Preserve numeric negatives, neutralize spreadsheet formulas in string cells.
        if (!(value instanceof Number) && ((!text.stripLeading().isEmpty() && "=+@-".indexOf(text.stripLeading().charAt(0)) >= 0) || text.startsWith("\t") || text.startsWith("\r"))) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
