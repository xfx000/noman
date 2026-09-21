package dev.qiqi.dataagent.tool;

import dev.qiqi.dataagent.chart.GenerateChartTool;
import dev.qiqi.dataagent.network.WebSearchTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

/** A fresh toolkit per request; optional schemas are exposed through native discovery. */
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
        toolkit.createToolGroup("files", "Preview uploaded CSV files and compute exact aggregates over all rows using a fileId.", false);
        toolkit.registration().agentTool(files).group("files").apply();
        if (charts.enabled()) {
            toolkit.createToolGroup("charts", "Generate bar, line and pie charts from a queryId returned by execute_sql.", false);
            toolkit.registration().agentTool(charts).group("charts").apply();
        }
        if (online && web.enabled()) {
            toolkit.createToolGroup("web", "Search public web sources for current external context and cite URLs. User enabled online search for this turn.", false);
            toolkit.registration().agentTool(web).group("web").apply();
        }
        return toolkit;
    }
}
