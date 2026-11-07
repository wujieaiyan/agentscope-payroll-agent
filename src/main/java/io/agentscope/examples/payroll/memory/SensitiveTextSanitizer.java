package io.agentscope.examples.payroll.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感文本清洗：删除含金额、比例、银行卡号、身份证号的整句。
 *
 * <p>会话压缩摘要与向量记忆共用同一套规则，保证任何进入提示词的历史片段都不携带过期金额。
 */
public final class SensitiveTextSanitizer {

    private static final Pattern MONEY_WITH_UNIT =
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:元|万元|万)");
    private static final Pattern PERCENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern DECIMAL_AMOUNT = Pattern.compile("\\d[\\d,]*\\.\\d{2,}");
    private static final Pattern LARGE_NUMBER = Pattern.compile("\\d{5,}");
    private static final String MASK = "[金额已省略]";

    private SensitiveTextSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 先整体标记金额，避免千分位逗号被后面的分句切碎，只删掉半个数字。
        String marked = MONEY_WITH_UNIT.matcher(text).replaceAll("§");
        marked = PERCENT.matcher(marked).replaceAll("§");
        marked = DECIMAL_AMOUNT.matcher(marked).replaceAll("§");
        marked = LARGE_NUMBER.matcher(marked).replaceAll("§");
        List<String> kept = new ArrayList<>();
        for (String part : marked.split("[，,。；;\\n]")) {
            String clause = part.trim();
            if (clause.isEmpty()
                    || clause.contains("§")
                    || clause.contains("银行卡")
                    || clause.contains("卡号")
                    || clause.contains("身份证")) {
                continue;
            }
            kept.add(clause);
        }
        return String.join("，", kept);
    }

    /**
     * 索引用清洗：把金额与比例替换为占位符而不是丢弃整句，保留问题语义用于召回。
     *
     * <p>银行卡号、身份证号所在分句仍然整句丢弃，避免任何证件信息进入向量库。
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String redacted = MONEY_WITH_UNIT.matcher(text).replaceAll(MASK);
        redacted = PERCENT.matcher(redacted).replaceAll(MASK);
        redacted = DECIMAL_AMOUNT.matcher(redacted).replaceAll(MASK);
        redacted = LARGE_NUMBER.matcher(redacted).replaceAll(MASK);
        List<String> kept = new ArrayList<>();
        for (String part : redacted.split("[，,。；;\\n]")) {
            String clause = part.trim();
            if (clause.isEmpty()
                    || clause.contains("银行卡")
                    || clause.contains("卡号")
                    || clause.contains("身份证")) {
                continue;
            }
            kept.add(clause);
        }
        return String.join("，", kept);
    }
}
