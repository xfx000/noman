package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.schema.SchemaCatalog;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CatalogTools {
    private final SchemaCatalog catalog;
    private final ObjectMapper mapper;

    public CatalogTools(SchemaCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Tool(name = "list_tables", description = "List the business tables exposed to the data agent.", readOnly = true)
    public String listTables() {
        return json(catalog.listTables());
    }

    @Tool(name = "inspect_schema",
            description = "Inspect all exposed tables, their real columns and foreign keys, and the database product in one call before writing SQL.",
            readOnly = true)
    public String inspectSchema() {
        return json(catalog.inspect());
    }

    @Tool(name = "describe_table",
            description = "Describe the real columns and foreign keys of one exposed table before writing SQL.",
            readOnly = true)
    public String describeTable(@ToolParam(name = "table", description = "Exact table name") String table) {
        return json(catalog.describe(table));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize schema", e);
        }
    }
}
