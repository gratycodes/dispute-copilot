package io.github.gratycodes.disputes.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Thin wrapper so repositories read and write JSONB without repeating error handling. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public JsonNode read(String json) {
        return json == null ? null : mapper.readTree(json);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
