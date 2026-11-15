package io.agentscope.examples.payroll.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 轻量字面切词：英文/数字按词、中文按二元组，供引用相关性与召回主题判断共用。 */
public final class LexicalTokens {

    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Set<String> STOPWORDS =
            Set.of(
                    "如何", "什么", "怎么", "怎样", "哪些", "是否", "可以", "需要", "请问", "一下",
                    "这个", "那个", "以及", "或者", "为什么", "多少", "介绍", "说明", "帮我",
                    "告诉", "相关", "内容", "问题", "具体", "流程", "规定", "制度",
                    "the", "and", "for", "with", "how", "what", "why", "does", "can", "are",
                    "was", "not", "you", "this", "that", "from", "into", "about");

    private LexicalTokens() {
    }

    /** 英文/数字词 + 中文单字与二元组，去掉常见虚词。 */
    public static Set<String> terms(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_TOKEN.matcher(lower);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        String cjk = lower.replaceAll("[^\\p{IsHan}]", " ");
        for (String run : cjk.split("\\s+")) {
            if (run.isBlank()) {
                continue;
            }
            if (run.length() == 1) {
                if (!STOPWORDS.contains(run)) {
                    tokens.add(run);
                }
                continue;
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                String gram = run.substring(i, i + 2);
                if (!STOPWORDS.contains(gram)) {
                    tokens.add(gram);
                }
            }
        }
        return tokens;
    }

    /** 只保留英文/数字词，用于判断用户是否点名了某个具体主题（如 Kafka）。 */
    public static List<String> asciiTerms(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        Matcher matcher = ASCII_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOPWORDS.contains(token) && !terms.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }
}
