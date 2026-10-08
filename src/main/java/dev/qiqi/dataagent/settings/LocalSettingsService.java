package dev.qiqi.dataagent.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

@Service
@Profile("local")
public class LocalSettingsService {
    private final Environment env;
    private final Path file;
    public LocalSettingsService(Environment env,
            @Value("${qiqi.settings.file:application-local.properties}") String path) {
        this.env = env;
        this.file = Path.of(path).toAbsolutePath();
    }
    private Properties read() throws IOException {
        Properties values = new Properties();
        if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
        return values;
    }
    private String value(Properties saved, String key, String fallback) {
        return saved.getProperty(key, env.getProperty(key, fallback));
    }
    public synchronized Map<String, Object> view() {
        try {
            Properties saved = read();
            boolean pending = saved.stringPropertyNames().stream().anyMatch(key ->
                    !key.equals("qiqi.creative.hyperframes.enabled") && !Objects.equals(saved.getProperty(key), env.getProperty(key)));
            String url = env.getProperty("spring.datasource.url", "");
            return Map.of("restartRequired", pending,
                "creative", Map.of("hyperframesEnabled", Boolean.parseBoolean(value(saved,"qiqi.creative.hyperframes.enabled","false"))),
                "model", Map.of("provider", value(saved,"qiqi.model.provider","dashscope"),
                    "baseUrl", value(saved,"qiqi.model.base-url",""),
                    "name", value(saved,"qiqi.model.name","qwen-plus"),
                    "keyConfigured", !value(saved,"qiqi.model.api-key","").isBlank()),
                "database", Map.of("kind", url.startsWith("jdbc:h2:mem:") ? "H2 内存数据库" : "JDBC 数据库",
                    "location", url.startsWith("jdbc:h2:mem:") ? "本机 · 销售示例数据" : "服务端配置的数据源",
                    "maxRows", value(saved,"qiqi.query.max-rows","200"),
                    "timeoutSeconds", value(saved,"qiqi.query.timeout","10s").replace("s", "")));
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"无法读取本地设置"); }
    }
    public record ModelInput(String provider, String baseUrl, String name, String apiKey) {}
    public record DatabaseInput(int maxRows, int timeoutSeconds) {}
    public record CreativeInput(boolean hyperframesEnabled) {}
    public synchronized boolean hyperframesEnabled() {
        try { return Boolean.parseBoolean(value(read(), "qiqi.creative.hyperframes.enabled", "false")); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"无法读取本地设置"); }
    }
    public synchronized void saveCreative(CreativeInput input) {
        save(Map.of("qiqi.creative.hyperframes.enabled", Boolean.toString(input.hyperframesEnabled())));
    }
    public synchronized void saveModel(ModelInput input) {
        if (!Set.of("openai","dashscope").contains(Objects.toString(input.provider(),"")))
            throw new IllegalArgumentException("请选择有效的模型接口");
        String name = Objects.toString(input.name(),"").trim();
        if (name.isEmpty() || name.length()>160) throw new IllegalArgumentException("请填写模型名称（最多 160 字）");
        String url = Objects.toString(input.baseUrl(),"").trim();
        if (!url.isEmpty()) {
            try {
                URI uri=URI.create(url);
                if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("接口地址须为不含账号、查询参数的 HTTP 或 HTTPS 地址"); }
        }
        if (input.provider().equals("openai") && url.isEmpty()) throw new IllegalArgumentException("OpenAI 兼容接口需要填写地址");
        String key=Objects.toString(input.apiKey(),"").trim();
        if (key.length()>4096 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("Key 格式无效");
        Map<String,String> patch=new HashMap<>();
        patch.put("qiqi.model.provider", input.provider()); patch.put("qiqi.model.name",name);
        patch.put("qiqi.model.base-url",url);
        if (!key.isBlank()) patch.put("qiqi.model.api-key",key);
        save(patch);
    }
    public synchronized void saveDatabase(DatabaseInput input) {
        if(input.maxRows()<1 || input.maxRows()>1000 || input.timeoutSeconds()<1 || input.timeoutSeconds()>60)
            throw new IllegalArgumentException("最大行数须为 1–1000，超时须为 1–60 秒");
        save(Map.of("qiqi.query.max-rows",Integer.toString(input.maxRows()),
                "qiqi.query.timeout",input.timeoutSeconds()+"s"));
    }
    private void save(Map<String,String> patch) {
        Path temp=null;
        try {
            Properties values=read(); values.putAll(patch);
            temp=Files.createTempFile(file.getParent(),".qiqi-settings-",".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(Writer writer=Files.newBufferedWriter(temp)) { values.store(writer,"Local settings - never commit credentials"); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"无法保存本地设置"); }
        finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) {} }
    }
}
