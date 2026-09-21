package dev.qiqi.dataagent;

import dev.qiqi.dataagent.config.QiqiProperties;
import dev.qiqi.dataagent.chart.ChartProperties;
import dev.qiqi.dataagent.network.WebSearchProperties;
import dev.qiqi.dataagent.storage.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({QiqiProperties.class, ChartProperties.class, WebSearchProperties.class, StorageProperties.class})
public class QiqiDataAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(QiqiDataAgentApplication.class, args);
    }
}
