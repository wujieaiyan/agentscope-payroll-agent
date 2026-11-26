package io.agentscope.examples.payroll.kb.parse;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import java.util.List;

/** 识别失败分类：NO_ENGINE / NO_TEXT / OVER_LIMIT / ENGINE_ERROR，附带各档位尝试记录。 */
public class OcrFailure extends RuntimeException {

    public static final String NO_ENGINE = "NO_ENGINE";
    public static final String NO_TEXT = "NO_TEXT";
    public static final String OVER_LIMIT = "OVER_LIMIT";
    public static final String ENGINE_ERROR = "ENGINE_ERROR";

    private final String code;
    private final List<ExtractionMeta.Attempt> attempts;

    public OcrFailure(String code, String message, List<ExtractionMeta.Attempt> attempts) {
        super(message);
        this.code = code;
        this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public String code() {
        return code;
    }

    public List<ExtractionMeta.Attempt> attempts() {
        return attempts;
    }
}
