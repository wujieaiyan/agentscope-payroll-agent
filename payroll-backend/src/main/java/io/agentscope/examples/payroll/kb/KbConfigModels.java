package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;

/**
 * 知识库分块/检索配置。使用 public 字段承载 JSON，缺省值即默认配置；
 * 全局 SNAKE_CASE 会改写字段名，这里显式保持 camelCase 与前端一致。
 *
 * <p>解析配置由 {@link ParseConfig} 提供，Web 与独立解析进程共用同一套结构。
 */
public final class KbConfigModels {

    private KbConfigModels() {
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class ChunkConfig {
        public String strategy = "DEFAULT";
        public CommonChunkConfig common = new CommonChunkConfig();
        public ByLineConfig byLine = new ByLineConfig();
        public ParentChildConfig parentChild = new ParentChildConfig();
        public TitleEnhancedConfig titleEnhanced = new TitleEnhancedConfig();
        public CustomConfig custom = new CustomConfig();
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class CommonChunkConfig {
        public int maxTokens = 512;
        public int overlapTokens = 64;
        public int minTokens = 50;
        public boolean keepTableWhole = true;
        public String tokenizer = "cl100k_base";
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class ByLineConfig {
        public int maxTokens = 512;
        public boolean dropEmpty = true;
        public boolean mergeShortLines = true;
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class ParentChildConfig {
        public int parentTokens = 1024;
        public int childTokens = 256;
        public int childOverlap = 32;
        public int maxChildrenPerParent = 8;
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class TitleEnhancedConfig {
        public String template = "{doc} > {h1} > {h2}";
        public boolean keepTitlePath = true;
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class CustomConfig {
        public String separator = "\\n\\n";
        public boolean keepSeparator = true;
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class RetrievalConfig {
        public boolean hybridEnabled = true;
        public double vectorWeight = 0.7;
        public boolean rerankEnabled = true;
        public String rerankModel = "local-hash-rerank";
        public CitationConfig citation = new CitationConfig();
        public int recallTopK = 20;
        public int rerankTopN = 5;
        public double similarityThreshold = 0.30;
        public String noAnswerStrategy = "EMPTY";
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class CitationConfig {
        public boolean enabled = true;
        public String granularity = "PARAGRAPH";
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public static class ConfigPayload {
        public ParseConfig parse;
        public ChunkConfig chunk;
        public RetrievalConfig retrieval;
    }

    public static ParseConfig defaultParse() {
        return ParseConfig.defaults();
    }

    public static ChunkConfig defaultChunk() {
        return new ChunkConfig();
    }

    public static RetrievalConfig defaultRetrieval() {
        return new RetrievalConfig();
    }
}
