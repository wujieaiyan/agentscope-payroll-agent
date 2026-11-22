package io.agentscope.examples.payroll.domain;

import java.util.List;

/**
 * 文本提取溯源：记录实际生效的引擎、模型与质量门结果，并保留每个候选档位的尝试记录。
 *
 * <p>sourceSha256 用于按原图重跑并对比，不作为安全凭据。
 */
public record ExtractionMeta(
        String engine,
        String model,
        String version,
        String languages,
        Integer psm,
        Double confidence,
        long latencyMs,
        String sourceSha256,
        int chars,
        int words,
        int lowConfidenceWords,
        boolean gatePassed,
        String gateDetail,
        List<Attempt> attempts) {

    public ExtractionMeta {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /** Word/Excel 等原生解析无需引擎链，只记录类型与耗时。 */
    public static ExtractionMeta nativeParse(
            String ext, String sha256, long latencyMs, int chars) {
        return new ExtractionMeta(
                "native/" + ext,
                null,
                null,
                null,
                null,
                null,
                latencyMs,
                sha256,
                chars,
                0,
                0,
                true,
                null,
                List.of());
    }

    /** 失败也落溯源：保留每个档位的尝试与最终失败原因，便于前端解释与重跑。 */
    public static ExtractionMeta failure(String detail, List<Attempt> attempts) {
        return new ExtractionMeta(
                null, null, null, null, null, null, 0, null, 0, 0, 0, false, detail, attempts);
    }

    public record Attempt(
            String engine,
            String model,
            Double confidence,
            long latencyMs,
            int chars,
            boolean gatePassed,
            String gateDetail,
            String error) {
    }
}
