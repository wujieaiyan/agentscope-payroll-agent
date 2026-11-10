package io.agentscope.examples.payroll.kb;

import java.util.ArrayList;
import java.util.List;

/** 解析结果：全文、页区间与标题偏移，供分块定位页码与标题路径。 */
public record KbParsedDocument(
        String text, int pageCount, List<PageRange> pages, List<Heading> headings) {

    public KbParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        headings = headings == null ? List.of() : List.copyOf(headings);
    }

    public record PageRange(int pageNo, int start, int end) {
    }

    public record Heading(int offset, int level, String text) {
    }

    public Integer pageAt(int offset) {
        for (PageRange range : pages) {
            if (offset >= range.start() && offset < range.end()) {
                return range.pageNo();
            }
        }
        return pages.isEmpty() ? null : pages.get(pages.size() - 1).pageNo();
    }

    /** 计算 offset 处生效的标题路径，最多保留两级标题。 */
    public String titlePathAt(int offset) {
        List<Heading> active = new ArrayList<>();
        for (Heading heading : headings) {
            if (heading.offset() > offset) {
                break;
            }
            while (!active.isEmpty()
                    && active.get(active.size() - 1).level() >= heading.level()) {
                active.remove(active.size() - 1);
            }
            if (heading.level() <= 2) {
                active.add(heading);
            }
        }
        if (active.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (Heading heading : active) {
            if (path.length() > 0) {
                path.append(" > ");
            }
            path.append(heading.text());
        }
        return path.toString();
    }
}
