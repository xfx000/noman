package dev.qiqi.dataagent.video;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qiqi.dataagent.agent.CreativeFeature;
import dev.qiqi.dataagent.chart.ChartQueryStore;
import dev.qiqi.dataagent.observability.ExecutionJournal;
import dev.qiqi.dataagent.observability.RunTraceStore;
import dev.qiqi.dataagent.query.QueryResult;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** A user-accepted hook from a completed, evidence-backed report to a fixed video template. */
@Service
public class ReportVideoService {
    private static final Duration RENDER_TIMEOUT = Duration.ofMinutes(3);
    private static final long MAX_VIDEO_BYTES = 30_000_000;
    private final CreativeFeature creative;
    private final RunTraceStore runs;
    private final ChartQueryStore evidence;
    private final LocalWorkspace workspace;
    private final Path cli;
    private final Path ffmpegDir;
    private final Semaphore renderer = new Semaphore(1);

    public ReportVideoService(CreativeFeature creative, RunTraceStore runs, ChartQueryStore evidence, LocalWorkspace workspace,
                              @org.springframework.beans.factory.annotation.Value("${qiqi.creative.hyperframes.cli:tools/report-video/node_modules/.bin/hyperframes}") String cliPath,
                              @org.springframework.beans.factory.annotation.Value("${qiqi.creative.hyperframes.ffmpeg-dir:}") String ffmpegPath) {
        this.creative = creative; this.runs = runs; this.evidence = evidence; this.workspace = workspace;
        this.cli = Path.of(cliPath).toAbsolutePath().normalize();
        this.ffmpegDir = ffmpegPath.isBlank() ? null : Path.of(ffmpegPath).toAbsolutePath().normalize();
    }

    public Offer offer(long owner, String runId) {
        if (!creative.enabled()) return new Offer(false, "请先在设置中开启创意功能。", null, null);
        var run = runs.require(runId, owner);
        var report = runs.execution(runId, owner);
        if (!run.conversationId().equals(report.conversationId()) ||
                !List.of("SUCCEEDED", "PARTIAL").contains(report.status()) ||
                report.finalNarrationId() == null || report.text() == null || report.text().isBlank())
            return new Offer(false, "分析报告尚未完成。", null, null);
        String ownerKey = Long.toString(owner);
        var existing = workspace.read(ownerKey, "report-videos", runId, VideoArtifact.class).orElse(null);
        if (existing != null && Files.isRegularFile(workspace.artifactFile(ownerKey, "report-video-files", existing.id(), "mp4")))
            return new Offer(true, null, existing.queryId(), existing.id());
        String selected = null;
        int bestScore = Integer.MIN_VALUE;
        for (String queryId : orderedQueryIds(report)) {
            try {
                QueryResult result = evidence.require(ownerKey, LocalWorkspace.key(report.conversationId()), queryId);
                if (values(result).isEmpty()) continue;
                String metric = metricColumn(result).toLowerCase(java.util.Locale.ROOT);
                int score = result.rows().size() > 1 ? 2 : 0;
                if (metric.contains("growth") || metric.contains("增长")) score += 6;
                else if (metric.contains("revenue") || metric.contains("sales") || metric.contains("收入") || metric.contains("销售额")) score += 3;
                if (report.charts().stream().anyMatch(chart -> queryId.equals(chart.path("queryId").asText()))) score += 10;
                if (score > bestScore) { bestScore = score; selected = queryId; }
            } catch (IllegalArgumentException ignored) { /* Another stored evidence item may be usable. */ }
        }
        if (selected != null) return new Offer(true, null, selected, null);
        return new Offer(false, "报告没有可制作图表的查询证据。", null, null);
    }

    public VideoArtifact render(long owner, String runId) {
        Offer offer = offer(owner, runId);
        if (!offer.available()) throw new IllegalArgumentException(offer.reason());
        String ownerKey = Long.toString(owner);
        if (offer.videoId() != null)
            return workspace.read(ownerKey, "report-videos", runId, VideoArtifact.class).orElseThrow();
        if (!renderer.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有视频正在生成，请稍后重试。");
        try {
            var report = runs.execution(runId, owner);
            var result = evidence.require(ownerKey, LocalWorkspace.key(report.conversationId()), offer.queryId());
            String id = UUID.randomUUID().toString();
            Path output = workspace.artifactFile(ownerKey, "report-video-files", id, "mp4");
            Path source = workspace.artifactFile(ownerKey, "report-video-files", id, "html");
            Files.createDirectories(output.getParent());
            Files.writeString(source, composition(result), StandardCharsets.UTF_8);
            Path log = Files.createTempFile("qiqi-hyperframes-", ".log");
            try {
                if (!Files.isExecutable(cli)) throw new IllegalStateException("未安装本地 HyperFrames 渲染器，请在 tools/report-video 运行 npm ci。");
                ProcessBuilder command = new ProcessBuilder(cli.toString(), "render",
                        "-c", source.getFileName().toString(), "-o", output.toString(), "-q", "draft", "-w", "1");
                command.directory(source.getParent().toFile());
                if (ffmpegDir != null) {
                    if (!Files.isExecutable(ffmpegDir.resolve("ffmpeg")) || !Files.isExecutable(ffmpegDir.resolve("ffprobe")))
                        throw new IllegalStateException("配置的 FFmpeg 目录缺少 ffmpeg 或 ffprobe。");
                    command.environment().put("PATH", ffmpegDir + java.io.File.pathSeparator + command.environment().getOrDefault("PATH", ""));
                }
                command.redirectErrorStream(true).redirectOutput(log.toFile());
                Process process = command.start();
                if (!process.waitFor(RENDER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("视频生成超时，请稍后重试。");
                }
                if (process.exitValue() != 0 || !Files.isRegularFile(output))
                    throw new IllegalStateException("HyperFrames 渲染失败，请检查 Node.js、HyperFrames CLI 和 FFmpeg 安装。" +
                            diagnostic(log));
                if (Files.size(output) > MAX_VIDEO_BYTES) throw new IllegalStateException("生成的视频超过 30 MB 限制。");
                VideoArtifact artifact = new VideoArtifact(id, runId, result.queryId(), 15, "1280×720");
                workspace.write(ownerKey, "report-videos", runId, artifact);
                return artifact;
            } finally { Files.deleteIfExists(log); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频生成已中断。", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存视频文件。", e);
        } finally { renderer.release(); }
    }

    public Path video(long owner, String runId, String id) {
        VideoArtifact artifact = workspace.read(Long.toString(owner), "report-videos", runId, VideoArtifact.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!artifact.id().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = workspace.artifactFile(Long.toString(owner), "report-video-files", id, "mp4");
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return path;
    }

    private static List<String> orderedQueryIds(ExecutionJournal.Snapshot report) {
        List<String> ids = new ArrayList<>();
        for (JsonNode chart : report.charts()) if (chart.path("queryId").isTextual()) ids.add(chart.path("queryId").asText());
        for (JsonNode item : report.evidence()) if (item.path("queryId").isTextual() && !ids.contains(item.path("queryId").asText()))
            ids.add(item.path("queryId").asText());
        return ids;
    }

    private static List<Value> values(QueryResult result) {
        String numeric = metricColumn(result);
        if (numeric == null) return List.of();
        String category = result.columns().stream().filter(column -> !column.equals(numeric) && result.rows().stream()
                .anyMatch(row -> row.get(column) != null && !(row.get(column) instanceof Number))).findFirst().orElse(null);
        List<Value> values = new ArrayList<>();
        int rowNumber = 0;
        for (Map<String, Object> row : result.rows()) {
            if (!(row.get(numeric) instanceof Number number)) continue;
            String label = category == null ? "第 " + (++rowNumber) + " 项" : String.valueOf(row.get(category));
            if (label.isBlank() || label.equals("null")) label = numeric;
            values.add(new Value(label, new BigDecimal(number.toString())));
        }
        values.sort(Comparator.comparing(Value::number).reversed());
        return values.stream().limit(5).toList();
    }

    static String metricColumn(QueryResult result) {
        List<String> numeric = result.columns().stream().filter(column -> result.rows().stream()
                .anyMatch(row -> row.get(column) instanceof Number)).toList();
        for (String column : numeric) {
            String name = column.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("growth_amount") || name.contains("revenue_growth") || name.contains("sales_growth")
                    || name.contains("change_amount") || name.contains("增长金额") || name.contains("增长额")) return column;
        }
        for (String column : numeric) {
            String name = column.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("revenue") || name.contains("sales_amount") || name.contains("销售额") || name.contains("收入"))
                return column;
        }
        return numeric.isEmpty() ? null : numeric.getLast();
    }

    private static String composition(QueryResult result) {
        List<Value> values = values(result);
        String rows = "";
        BigDecimal max = values.stream().map(v -> v.number().abs()).max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        if (max.signum() == 0) max = BigDecimal.ONE;
        for (Value value : values) {
            int width = Math.max(2, value.number().abs().multiply(BigDecimal.valueOf(100))
                    .divide(max, 0, java.math.RoundingMode.HALF_UP).intValue());
            rows += "<div class='bar-row'><span>" + escape(value.label()) + "</span><i style='width:" + width +
                    "%'></i><b>" + escape(value.number().toPlainString()) + "</b></div>";
        }
        Value top = values.getFirst();
        String metric = metricColumn(result);
        boolean growth = metric != null && (metric.toLowerCase(java.util.Locale.ROOT).contains("growth") || metric.contains("增长"));
        String label = (result.truncated() ? "当前预览" : "查询结果") + (growth ? "最大增长金额" : "最高指标值");
        String chartTitle = growth ? "增长金额对比" : "指标值对比";
        String query = escape(result.queryId());
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
                *{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif;color:#fff}
                #video{position:relative;width:1280px;height:720px;overflow:hidden;background:#071932}
                .clip{position:absolute;inset:0}.bg{background:radial-gradient(circle at 80%% 20%%,#2674b9,#092a54 55%%,#071932)}
                .scene{padding:95px 112px;animation:enter .65s ease-out both}
                @keyframes enter{from{opacity:0;transform:translateY(25px)}to{opacity:1;transform:translateY(0)}}
                .eyebrow{font-size:22px;color:#8ed4ff;letter-spacing:3px}.title{font-size:66px;font-weight:800;line-height:1.22;margin:54px 0 25px}
                .sub{font-size:28px;color:#b8d7ef;line-height:1.5}.metric{font-size:76px;font-weight:800;color:#74d6ff;margin:36px 0 15px}
                .bar-row{display:flex;align-items:center;gap:20px;margin:17px 0;font-size:23px}.bar-row span{width:230px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
                .bar-row i{display:block;height:26px;max-width:550px;background:linear-gradient(90deg,#36a9eb,#79e1ee);border-radius:8px}
                .bar-row b{min-width:135px;font-size:22px}.foot{position:absolute;left:112px;bottom:50px;font-size:19px;color:#9bb6cb}
                </style></head><body><div id="video" data-composition-id="qiqi-report-video" data-width="1280" data-height="720" data-duration="15" data-no-timeline>
                <div class="clip bg" id="background" data-start="0" data-duration="15" data-track-index="0"></div>
                <section class="clip scene" id="intro" data-start="0" data-duration="5" data-track-index="1"><div class="eyebrow">QIQI · 数据分析</div><h1 class="title">分析结论速览</h1><p class="sub">15 秒了解这份报告的关键数据</p></section>
                <section class="clip scene" id="metric" data-start="5" data-duration="5" data-track-index="1"><div class="eyebrow">关键发现</div><h1 class="title">%s</h1><div class="metric">%s</div><p class="sub">%s</p></section>
                <section class="clip scene" id="chart" data-start="10" data-duration="5" data-track-index="1"><div class="eyebrow">关键图表 · 查询结果</div><h1 class="title" style="font-size:43px;margin:20px 0">%s</h1>%s<div class="foot">查询证据 queryId：%s%s</div></section>
                </div></body></html>
                """.formatted(escape(label), escape(top.number().toPlainString()), escape(top.label()), escape(chartTitle), rows, query,
                result.truncated() ? " · 仅展示截断后的预览行" : "");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String diagnostic(Path log) {
        try {
            String text = Files.readString(log);
            return text.contains("No composition found") ? "（未找到视频工程）" : "";
        } catch (IOException ignored) { return ""; }
    }
    private record Value(String label, BigDecimal number) {}
    public record Offer(boolean available, String reason, String queryId, String videoId) {}
    public record VideoArtifact(String id, String runId, String queryId, int durationSeconds, String size) {}
}
