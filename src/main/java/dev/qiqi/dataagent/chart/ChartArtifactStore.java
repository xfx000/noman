package dev.qiqi.dataagent.chart;

import dev.qiqi.dataagent.storage.LocalWorkspace;
import org.springframework.stereotype.Component;

@Component
public class ChartArtifactStore {
    private final LocalWorkspace workspace;
    public ChartArtifactStore(LocalWorkspace workspace) { this.workspace = workspace; }
    public ChartArtifact save(String owner, ChartArtifact artifact) {
        workspace.write(owner, "charts", artifact.id(), artifact);
        return artifact.source().startsWith("data:image/png;base64,")
                ? new ChartArtifact(artifact.id(), artifact.queryId(), artifact.title(), artifact.type(), "/api/charts/" + artifact.id()) : artifact;
    }
    public ChartArtifact require(String owner, String id) {
        return workspace.read(owner, "charts", id, ChartArtifact.class)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
}
