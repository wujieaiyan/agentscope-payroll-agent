package io.agentscope.examples.payroll.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 审计快照脱敏：密码、银行卡、证件号、令牌等字段统一替换为 ***，非 JSON 请求体不落库。 */
public final class AuditSanitizer {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> SENSITIVE =
            Set.of(
                    "password",
                    "oldpassword",
                    "newpassword",
                    "passwordhash",
                    "bankaccount",
                    "bankaccountmasked",
                    "idcard",
                    "idnumber",
                    "token",
                    "secret",
                    "authorization");

    private AuditSanitizer() {
    }

    public static String json(Object value) {
        if (value == null) {
            return null;
        }
        return sanitize(JsonSupport.toJson(value), false);
    }

    /** 请求体可能不是 JSON（Excel 上传等），无法解析时返回 null，避免把二进制写进日志。 */
    public static String requestBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        return sanitize(rawBody, true);
    }

    private static String sanitize(String json, boolean strict) {
        try {
            JsonNode root = MAPPER.readTree(json);
            redact(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return strict ? null : "\"<unserializable>\"";
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isSensitive(name)) {
                    object.put(name, "***");
                } else {
                    redact(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AuditSanitizer::redact);
        }
    }

    private static boolean isSensitive(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE.contains(normalized);
    }
}
