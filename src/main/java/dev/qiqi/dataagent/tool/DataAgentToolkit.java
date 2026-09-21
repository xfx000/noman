package dev.qiqi.dataagent.tool;

import dev.qiqi.dataagent.chart.GenerateChartTool;
import dev.qiqi.dataagent.network.WebSearchTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

/** A fresh toolkit per request; files/web stay behind native discovery, charts are always-on when enabled. */
@Component
public class DataAgentToolkit {
    private final CatalogTools catalog;
    private final SqlTools sql;
    private final ExecuteSqlAgentTool execute;
    private final GenerateChartTool charts;
    private final WebSearchTool web;
    private final dev.qiqi.dataagent.files.AnalyzeFileTool files;
    public DataAgentToolkit(CatalogTools catalog, SqlTools sql, ExecuteSqlAgentTool execute, GenerateChartTool charts, WebSearchTool web, dev.qiqi.dataagent.files.AnalyzeFileTool files) {
        this.catalog = catalog; this.sql = sql; this.execute = execute; this.charts = charts; this.web = web; this.files = files;
    }
    public Toolkit create(boolean online) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(catalog);
        toolkit.registerTool(sql);
        toolkit.registerAgentTool(execute);
        toolkit.registerAgentTool(new TodoWriteAgentTool());
        if (charts.enabled()) {
            toolkit.registerAgentTool(charts);
        }
        toolkit.createToolGroup("files", "Preview uploaded CSV files and compute exact aggregates over all rows using a fileId.", false);
        toolkit.registration().agentTool(files).group("files").apply();
        if (online && web.enabled()) {
            toolkit.createToolGroup("web", "Search public web sources for current external context and cite URLs. User enabled online search for this turn.", false);
            toolkit.registration().agentTool(web).group("web").apply();
        }
        return toolkit;
    }
}
