package io.agentscope.examples.payroll.kb.ocr;

/**
 * 质量门：判断一次识别结果是否足以交给下游，而不是在词级提前丢弃文本。
 *
 * <p>阈值来自配置（黄金集校准），默认只拦截明显的空白与乱码输出。
 */
public final class OcrQualityGate {

    private final int minChars;
    private final double minAlnumRatio;
    private final double minCjkRatio;
    private final double minConfidence;

    public OcrQualityGate(
            int minChars, double minAlnumRatio, double minCjkRatio, double minConfidence) {
        this.minChars = Math.max(0, minChars);
        this.minAlnumRatio = clamp(minAlnumRatio);
        this.minCjkRatio = clamp(minCjkRatio);
        this.minConfidence = clamp(minConfidence);
    }

    public Verdict evaluate(String text, double confidence) {
        String trimmed = text == null ? "" : text.strip();
        int chars = trimmed.length();
        if (chars < minChars) {
            return new Verdict(false, "字符数 " + chars + " < " + minChars);
        }
        int meaningful = 0;
        int cjk = 0;
        int nonSpace = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            nonSpace++;
            if (Character.isLetterOrDigit(ch)) {
                meaningful++;
            }
            if (isCjk(ch)) {
                cjk++;
            }
        }
        double alnumRatio = nonSpace == 0 ? 0 : (double) meaningful / nonSpace;
        if (alnumRatio < minAlnumRatio) {
            return new Verdict(
                    false, String.format("有效字符占比 %.2f < %.2f", alnumRatio, minAlnumRatio));
        }
        if (minCjkRatio > 0) {
            double cjkRatio = nonSpace == 0 ? 0 : (double) cjk / nonSpace;
            if (cjkRatio < minCjkRatio) {
                return new Verdict(
                        false, String.format("中文占比 %.2f < %.2f", cjkRatio, minCjkRatio));
            }
        }
        if (minConfidence > 0 && confidence < minConfidence) {
            return new Verdict(
                    false, String.format("平均置信度 %.2f < %.2f", confidence, minConfidence));
        }
        return new Verdict(
                true,
                String.format(
                        "字符 %d，有效占比 %.2f，置信度 %.2f", chars, alnumRatio, confidence));
    }

    private static boolean isCjk(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    public record Verdict(boolean passed, String detail) {
    }
}
