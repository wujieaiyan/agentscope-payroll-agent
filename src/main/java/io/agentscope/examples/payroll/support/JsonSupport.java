package io.agentscope.examples.payroll.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 统一 JSON 序列化：对外使用 snake_case，便于模型稳定读取工具结果。 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    private JsonSupport() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize object", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getOriginalMessage(), e);
        }
    }

    public static <T> List<T> readList(String classpathResource, Class<T> elementType) {
        try (InputStream in =
                JsonSupport.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + classpathResource);
            }
            return MAPPER.readValue(
                    in,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + classpathResource, e);
        }
    }
}
