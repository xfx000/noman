package dev.qiqi.dataagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.regex.Pattern;

/** Bounded display projection; raw fragmented arguments are never published or persisted. */
final class DisplaySanitizer {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern SECRET = Pattern.compile("(?i).*(password|passwd|secret|token|api.?key|authorization|cookie|credential|email|phone|mobile).*");
    static String text(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)Bearer\\s+[^\\s\"<>]+", "Bearer [redacted]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}", "[redacted]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]")
                .replaceAll("(?i)(password|passwd|api[_-]?key|secret|token|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]");
    }
    static JsonNode clean(Object value) { return clean(JSON.valueToTree(value), 0, ""); }
    private static JsonNode clean(JsonNode node, int depth, String key) {
        if (SECRET.matcher(key).matches()) return TextNode.valueOf("[redacted]");
        if (depth > 8) return TextNode.valueOf("[内容过深，已省略]");
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            var fields = node.fields(); int count = 0;
            while (fields.hasNext() && count++ < 60) {
                var field = fields.next();
                if (field.getKey().equals("rows")) { result.put("rows", "[明细通过证据导出查看]"); continue; }
                result.set(field.getKey(), clean(field.getValue(), depth + 1, field.getKey()));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (int i = 0; i < Math.min(node.size(), 30); i++) result.add(clean(node.get(i), depth + 1, key));
            if (node.size() > 30) result.add("[其余项目已省略]");
            return result;
        }
        if (node.isTextual()) {
            String value = text(node.asText());
            if (key.toLowerCase().contains("sql")) value = value.replaceAll("'(?:''|[^'])*'", "'[值已隐藏]'");
            if (value.startsWith("data:")) value = "[二进制数据已省略]";
            return TextNode.valueOf(limit(value, 4000));
        }
        return node;
    }
    static String structured(String raw, boolean complete) {
        if (raw.isBlank()) return "";
        try {
            JsonNode value = JSON.readTree(raw);
            if (value == null) return "";
            if (value.isTextual()) { try { value = JSON.readTree(value.asText()); } catch (Exception ignored) { } }
            return limit(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(clean(value)), 6000);
        } catch (Exception ignored) {
            if (!complete || raw.stripLeading().startsWith("{") || raw.stripLeading().startsWith("["))
                return "[JSON 不完整或超过展示上限，原始片段已隐藏]";
            return limit(text(raw), 6000);
        }
    }
    static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "\n[已截断]"; }
}
