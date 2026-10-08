package dev.qiqi.dataagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qiqi.dataagent.chart.ChartArtifactStore;
import dev.qiqi.dataagent.chart.ChartQueryStore;
import dev.qiqi.dataagent.identity.IdentityService;
import dev.qiqi.dataagent.schema.SchemaCatalog;
import dev.qiqi.dataagent.storage.CsvExport;
import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final LocalWorkspace workspace;
    private final IdentityService identities;
    private final ChartArtifactStore charts;
    private final ChartQueryStore evidence;
    private final dev.qiqi.dataagent.files.CsvFiles files;
    private final SchemaCatalog schema;
    public WorkspaceController(LocalWorkspace workspace, IdentityService identities, ChartArtifactStore charts, ChartQueryStore evidence, dev.qiqi.dataagent.files.CsvFiles files, SchemaCatalog schema) {
        this.workspace = workspace; this.identities = identities; this.charts = charts; this.evidence = evidence; this.files = files; this.schema = schema;
    }
    private String owner(String username) { return Long.toString(identities.findActiveByUsername(username)
            .orElseThrow(() -> new SecurityException("Unknown or inactive demo user")).id()); }

    @GetMapping("/data-center")
    public Map<String, Object> dataCenter(@RequestHeader("X-Qiqi-User") String username) {
        owner(username);
        return schema.inspect();
    }

    @GetMapping("/history")
    public synchronized History history(@RequestHeader("X-Qiqi-User") String username) {
        return workspace.read(owner(username), "history", "workbench", History.class).orElse(new History(0, null));
    }
    @PutMapping("/history")
    public synchronized History saveHistory(@RequestHeader("X-Qiqi-User") String username, @RequestBody History request) {
        String owner = owner(username);
        History current = workspace.read(owner, "history", "workbench", History.class).orElse(new History(0, null));
        if (current.revision() != request.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "History changed in another tab; refresh before editing");
        JsonNode data = request.data();
        if (data == null || !data.path("chats").isArray() || data.path("chats").size() > 100 || data.toString().length() > 5_000_000)
            throw new IllegalArgumentException("History exceeds limits");
        History saved = new History(current.revision() + 1, data);
        workspace.write(owner, "history", "workbench", saved);
        return saved;
    }
    @GetMapping("/charts/{id}")
    public ResponseEntity<byte[]> chart(@RequestHeader("X-Qiqi-User") String username, @PathVariable String id) {
        var chart = charts.require(owner(username), id);
        if (!chart.source().startsWith("data:image/png;base64,")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(Base64.getDecoder().decode(chart.source().substring(22)));
    }
    @GetMapping("/evidence/{id}")
    public dev.qiqi.dataagent.query.QueryResult evidence(@RequestHeader("X-Qiqi-User") String username, @PathVariable String id, @RequestParam String conversationId) {
        return evidence.require(owner(username), LocalWorkspace.key(conversationId), id);
    }
    @GetMapping("/evidence/{id}/csv")
    public ResponseEntity<byte[]> csv(@RequestHeader("X-Qiqi-User") String username, @PathVariable String id, @RequestParam String conversationId) {
        var result = evidence(username, id, conversationId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=qiqi-result.csv")
                .header("X-Qiqi-Truncated", Boolean.toString(result.truncated())).cacheControl(CacheControl.noStore()).body(CsvExport.bytes(result));
    }
    @PostMapping("/files")
    public Map<String,Object> upload(@RequestHeader("X-Qiqi-User") String username, @RequestBody Upload request) {
        String owner = owner(username);
        String name = request.name() == null ? "" : request.name().toLowerCase(java.util.Locale.ROOT);
        var table = name.endsWith(".csv")
                ? files.upload(owner, LocalWorkspace.key(request.conversationId()), request.name(), request.content())
                : files.uploadWorkbook(owner, LocalWorkspace.key(request.conversationId()), request.name(), decodeWorkbook(request.base64()));
        var preview = files.analyze(table, "preview", null, null);
        evidence.remember(owner, LocalWorkspace.key(request.conversationId()), preview);
        return Map.of("fileId", table.id(), "name", table.name(), "columns", table.columns(), "totalRows", table.rows().size(), "preview", preview);
    }
    @GetMapping("/files")
    public java.util.List<dev.qiqi.dataagent.files.CsvFiles.FileSummary> files(
            @RequestHeader("X-Qiqi-User") String username, @RequestParam String conversationId) {
        return files.list(owner(username), LocalWorkspace.key(conversationId));
    }
    private static byte[] decodeWorkbook(String base64) {
        if (base64 == null || base64.length() > 2_800_000) throw new IllegalArgumentException("Excel 文件不能超过 2 MB。");
        try { return Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Excel 文件编码无效。"); }
    }
    public record Upload(String conversationId, String name, String content, String base64) {}
    public record History(long revision, JsonNode data) {}
}
