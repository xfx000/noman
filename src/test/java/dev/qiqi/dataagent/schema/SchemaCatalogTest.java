package dev.qiqi.dataagent.schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaCatalogTest {
    @Autowired SchemaCatalog catalog;

    @Test
    void describesActualLowercaseTablesAndForeignKeys() {
        for (var table : catalog.listTables()) {
            var schema = catalog.describe(table.get("name").toString());
            assertThat((List<?>) schema.get("columns")).isNotEmpty();
        }
        var order = catalog.describe("sales_order");
        assertThat(order.get("databaseProduct")).isEqualTo("H2");
        assertThat((List<?>) order.get("columns")).hasSize(7);
        assertThat((List<?>) order.get("foreignKeys"))
                .anyMatch(key -> key.equals(Map.of("column", "department_id", "references", "department.id")));
        var item = catalog.describe("sales_order_item");
        assertThat((List<?>) item.get("foreignKeys"))
                .anyMatch(key -> key.equals(Map.of("column", "order_id", "references", "sales_order.id")));
        var snapshot = catalog.inspect();
        assertThat(snapshot.get("databaseProduct")).isEqualTo("H2");
        assertThat((List<?>) snapshot.get("tables")).hasSameSizeAs(catalog.listTables());
    }
}
