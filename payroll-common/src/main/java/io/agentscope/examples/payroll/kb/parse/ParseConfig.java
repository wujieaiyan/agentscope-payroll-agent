package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库解析配置。使用 public 字段承载 JSON，缺省值即默认配置；
 * 全局 SNAKE_CASE 会改写字段名，这里显式保持 camelCase 与前端一致。
 *
 * <p>放在 payroll-common，解析服务与 Web 共用同一份类型，避免解析模块反向依赖 Web。
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class ParseConfig {
    public List<String> docTypes = new ArrayList<>(List.of("PDF", "DOCX", "XLSX", "MD"));
    public Map<String, TypeParseConfig> perType = new LinkedHashMap<>();
    public OcrConfig ocr = new OcrConfig();
    public ParseOptions options = new ParseOptions();

    public static ParseConfig defaults() {
        ParseConfig config = new ParseConfig();
        config.perType.put("PDF", type(true, "AUTO"));
        config.perType.put("DOCX", type(true, "NATIVE"));
        config.perType.put("XLSX", type(true, "NATIVE"));
        config.perType.put("MD", type(true, "NATIVE"));
        return config;
    }

    private static TypeParseConfig type(boolean enabled, String strategy) {
        TypeParseConfig config = new TypeParseConfig();
        config.enabled = enabled;
        config.strategy = strategy;
        return config;
    }
}
