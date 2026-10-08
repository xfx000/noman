package dev.qiqi.dataagent.schema;

import dev.qiqi.dataagent.config.QiqiProperties;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SchemaCatalog {
    private final DataSource dataSource;
    private final QiqiProperties properties;

    public SchemaCatalog(DataSource dataSource, QiqiProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public List<Map<String, Object>> listTables() {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String table : properties.exposedTables()) {
            tables.add(Map.of("name", table, "description", description(table)));
        }
        tables.sort(Comparator.comparing(row -> row.get("name").toString()));
        return tables;
    }

    public Map<String, Object> inspect() {
        String databaseProduct;
        try (var connection = dataSource.getConnection()) {
            databaseProduct = connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect exposed schema", e);
        }
        return Map.of(
                "databaseProduct", databaseProduct,
                "tables", listTables().stream().map(table -> describe(table.get("name").toString())).toList());
    }

    public Map<String, Object> describe(String requested) {
        String table = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (!properties.exposedTables().contains(table)) {
            throw new IllegalArgumentException("Table is not exposed: " + requested);
        }
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String metadataTable = metadata.storesUpperCaseIdentifiers()
                    ? table.toUpperCase(Locale.ROOT) : table;
            List<Map<String, Object>> columns = new ArrayList<>();
            String escape = metadata.getSearchStringEscape();
            String tablePattern = metadataTable.replace("_", escape + "_");
            try (var rs = metadata.getColumns(connection.getCatalog(), connection.getSchema(), tablePattern, null)) {
                while (rs.next()) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    column.put("name", rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    column.put("type", rs.getString("TYPE_NAME"));
                    column.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    columns.add(column);
                }
            }
            if (columns.isEmpty()) throw new IllegalStateException("No metadata found for table: " + table);
            List<Map<String, String>> foreignKeys = new ArrayList<>();
            try (var rs = metadata.getImportedKeys(connection.getCatalog(), connection.getSchema(), metadataTable)) {
                while (rs.next()) {
                    foreignKeys.add(Map.of(
                            "column", rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT),
                            "references", rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT) + "."
                                    + rs.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT)));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", table);
            result.put("databaseProduct", metadata.getDatabaseProductName());
            result.put("description", description(table));
            result.put("columns", columns);
            result.put("foreignKeys", foreignKeys);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect table " + table, e);
        }
    }

    private static String description(String table) {
        return switch (table) {
            case "department" -> "销售部门";
            case "customer" -> "客户资料";
            case "product" -> "商品与品类";
            case "sales_order" -> "订单日期、客户、部门及金额";
            case "sales_order_item" -> "订单商品明细";
            default -> "业务数据表";
        };
    }
}
