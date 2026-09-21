package dev.qiqi.dataagent.storage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.nio.file.Path;
@ConfigurationProperties("qiqi.storage")
public record StorageProperties(@DefaultValue(".qiqi") Path directory) {}
