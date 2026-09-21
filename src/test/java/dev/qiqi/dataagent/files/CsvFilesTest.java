package dev.qiqi.dataagent.files;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qiqi.dataagent.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
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
}
