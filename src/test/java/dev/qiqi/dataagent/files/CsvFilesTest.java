package dev.qiqi.dataagent.files;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import static org.assertj.core.api.Assertions.*;
class CsvFilesTest {
    @TempDir Path directory;
    CsvFiles service() { return new CsvFiles(new LocalWorkspace(new StorageProperties(directory), new ObjectMapper())); }
    @Test void quotedCsvSurvivesRestartAndAggregatesAllRowsWithOwnerAndSessionIsolation() {
        var files = service();
        var table = files.upload("1", "session", "sales.csv", "department,amount,note\r\nNorth,12.50,\"first, row\"\r\nNorth,7.5,\"multi\nline\"\r\nSouth,3,ok\r\n");
        var restored = service().require("1", "session", table.id());
        assertThat(restored.rows()).hasSize(3);
        assertThat(restored.rows().get(1).get(2)).isEqualTo("multi\nline");
        var aggregate = files.analyze(restored, "sum", "amount", "department");
        assertThat(aggregate.truncated()).isFalse();
        assertThat((BigDecimal)aggregate.rows().getFirst().get("value")).isEqualByComparingTo("20");
        assertThatThrownBy(() -> service().require("2", "session", table.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().require("1", "another", table.id())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidNumericDataQuotesHeadersAndLimitsAreRejected() {
        assertThatThrownBy(() -> service().upload("1", "s", "x.csv", "a,a\n1,2")).hasMessageContaining("列名");
        assertThatThrownBy(() -> CsvFiles.parse("a,b\n\"unterminated")).hasMessageContaining("引号");
        assertThatThrownBy(() -> service().upload("1", "s", "x.csv", "a,b\n1")).hasMessageContaining("列数");
        assertThatThrownBy(() -> service().upload("1", "s", "x.csv", "x".repeat(2 * 1024 * 1024 + 1))).hasMessageContaining("2 MB");
        var table = service().upload("1", "s", "x.csv", "category,amount\na,=1+1");
        assertThatThrownBy(() -> service().analyze(table, "sum", "amount", null)).hasMessageContaining("非数值");
    }
    @Test void previewIsExplicitlyTruncatedButAggregationUsesEntireFile() {
        var table = service().upload("1", "s", "x.csv", "amount\n" + "1\n".repeat(40));
        assertThat(service().analyze(table, "preview", null, null).truncated()).isTrue();
        assertThat(service().analyze(table, "sum", "amount", null).rows().getFirst().get("value")).isEqualTo(new BigDecimal("40"));
    }
    @Test void fileDirectoryOnlyListsTheOwnersCurrentSessionWithoutRows() {
        var files = service();
        var own = files.upload("owner", "session-a", "own.csv", "name,amount\nA,2");
        files.upload("owner", "session-b", "another.csv", "name\nB");
        files.upload("other", "session-a", "private.csv", "name\nC");
        assertThat(service().list("owner", "session-a"))
                .containsExactly(new CsvFiles.FileSummary(own.id(), "own.csv", java.util.List.of("name", "amount"), 1));
    }
    @Test void xlsxAndXlsUseSameOwnerScopeAndAggregationWithoutEvaluatingFormulas() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            byte[] bytes;
            try (Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("sales");
                var header = sheet.createRow(0); header.createCell(0).setCellValue("department"); header.createCell(1).setCellValue("amount");
                var first = sheet.createRow(1); first.createCell(0).setCellValue("North"); first.createCell(1).setCellValue(12.5);
                var second = sheet.createRow(2); second.createCell(0).setCellValue("North"); second.createCell(1).setCellValue(7.5);
                workbook.write(output); bytes = output.toByteArray();
            }
            var files = service();
            var table = files.uploadWorkbook("1", "session", "sales." + (legacy ? "xls" : "xlsx"), bytes);
            assertThat((BigDecimal) files.analyze(table, "sum", "amount", "department").rows().getFirst().get("value"))
                    .isEqualByComparingTo("20");
            assertThat(files.analyze(table, "preview", null, null).source().get("type")).isEqualTo("excel");
            assertThatThrownBy(() -> files.require("2", "session", table.id())).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
