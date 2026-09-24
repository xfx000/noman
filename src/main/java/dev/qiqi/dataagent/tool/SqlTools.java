package dev.qiqi.dataagent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

@Component
public class SqlTools {
    private final ObjectMapper mapper;

    public SqlTools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Tool(name = "current_time",
            description = "Return current time in the configured server timezone for relative-date questions.", readOnly = true)
    public String currentTime() {
        return json(Map.of("time", OffsetDateTime.now(ZoneId.systemDefault()).toString(),
                "zone", ZoneId.systemDefault().getId()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize tool result", e);
        }
    }
}
