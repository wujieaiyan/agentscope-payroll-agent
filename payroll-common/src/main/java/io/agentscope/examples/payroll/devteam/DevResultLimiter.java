package io.agentscope.examples.payroll.devteam;

import java.util.List;

/**
 * 工具结果截断（P0-6/C3）：读取按字符数、单行按长度、检索命中与列表按条数封顶，
 * 防止超大文件或宽泛 glob 一次吃掉上下文预算；截断处保留可操作提示。
 */
public final class DevResultLimiter {

    public static final int MAX_READ_CHARS = 40_000;
    public static final int MAX_GREP_LINE_CHARS = 500;
    public static final int MAX_GREP_MATCHES = 300;
    public static final int MAX_LIST_ENTRIES = 1_000;

    public static final String READ_MARKER = "\n…[内容已截断：仅显示前 " + MAX_READ_CHARS + " 字符，可用 offset/limit 继续读取]";
    public static final String LINE_MARKER = "…[行已截断]";

    private DevResultLimiter() {}

    /** 截断结果；{@code omitted} 为未返回的条数。 */
    public record Limited<T>(List<T> items, int omitted) {}

    public static String truncateRead(String content) {
        if (content == null || content.length() <= MAX_READ_CHARS) {
            return content;
        }
        int cut = MAX_READ_CHARS;
        if (Character.isHighSurrogate(content.charAt(cut - 1))) {
            cut--;
        }
        return content.substring(0, cut) + READ_MARKER;
    }

    public static String truncateLine(String line) {
        if (line == null || line.length() <= MAX_GREP_LINE_CHARS) {
            return line;
        }
        int cut = MAX_GREP_LINE_CHARS;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--;
        }
        return line.substring(0, cut) + LINE_MARKER;
    }

    public static <T> Limited<T> limit(List<T> items, int max) {
        if (items == null || items.isEmpty()) {
            return new Limited<>(List.of(), 0);
        }
        if (items.size() <= max) {
            return new Limited<>(List.copyOf(items), 0);
        }
        return new Limited<>(List.copyOf(items.subList(0, max)), items.size() - max);
    }

    public static String omittedNote(int omitted) {
        return "[护栏] 结果已截断：另有 " + omitted + " 条未显示，请收窄 pattern/path/glob";
    }
}
