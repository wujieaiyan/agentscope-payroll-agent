package io.agentscope.payroll.parser.ocr;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import java.util.List;

/** 档位链最终生效的识别结果，含质量门结论与全部档位尝试。 */
public record ImageTextResult(
        String text,
        String engine,
        String model,
        String version,
        String languages,
        Integer psm,
        double confidence,
        long latencyMs,
        int words,
        int lowConfidenceWords,
        String sha256,
        boolean gatePassed,
        String gateDetail,
        List<ExtractionMeta.Attempt> attempts,
        boolean cached) {

    public ImageTextResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public ExtractionMeta toMeta(int chars) {
        return new ExtractionMeta(
                engine,
                model,
                version,
                languages,
                psm,
                confidence,
                latencyMs,
                sha256,
                chars,
                words,
                lowConfidenceWords,
                gatePassed,
                gateDetail,
                attempts);
    }
}
