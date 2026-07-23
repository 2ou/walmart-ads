package com.ai.walmartads.importing.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonPayloadWriter {

    private final ObjectMapper objectMapper;

    public JsonPayloadWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化源字段", exception);
        }
    }
}
