package io.agentscope.examples.payroll.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbConfigModels.ChunkConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.CommonChunkConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.CustomConfig;
import io.agentscope.examples.payroll.kb.KbEnums.ChunkLevel;
import io.agentscope.examples.payroll.kb.KbParsedDocument.Heading;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 五种分块策略；token 数为估算值（CJK 每字 1，拉丁字符 4 个约 1 个 token）。 */
@Service
public class KbChunkingService {

    private final ObjectMapper objectMapper;

    public KbChunkingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Draft(
            ChunkLevel level,
            Integer parentIndex,
            int seq,
            String content,
            int tokenCount,
            Integer charStart,
            Integer charEnd,
            Integer pageNo,
            String titlePath,
            String contentHash,
            String metadata) {
    }

    private record Span(String text, int tokens, int start, int end) {
    }

    private record Unit(String text, int tokens, int start, int end) {
    }

    public List<Draft> chunk(KbParsedDocument document, String documentName, ChunkConfig config) {
        if (document == null || document.text() == null || document.text().isBlank()) {
            return List.of();
        }
        String strategy = config.strategy == null ? "DEFAULT" : config.strategy.toUpperCase(Locale.ROOT);
        CommonChunkConfig common = config.common;
        List<Draft> drafts =
                switch (strategy) {
                    case "BY_LINE" -> byLine(document, config);
                    case "PARENT_CHILD" -> parentChild(document, config);
                    case "TITLE_ENHANCED" -> titleEnhanced(document, config);
                    case "CUSTOM" -> custom(document, config);
                    default -> defaultChunks(document, common);
                };
        List<Draft> result = new ArrayList<>(drafts.size());
        int seq = 0;
        for (Draft draft : drafts) {
            String content = draft.content().strip();
            if (content.isEmpty()) {
                seq++;
                continue;
            }
            result.add(
                    new Draft(
                            draft.level(),
                            draft.parentIndex(),
                            ++seq,
                            content,
                            estimateTokens(content),
                            draft.charStart(),
                            draft.charEnd(),
                            draft.pageNo(),
                            draft.titlePath(),
                            sha256(content),
                            metadata(strategy, documentName, draft.titlePath())));
        }
        return result;
    }

    private List<Draft> defaultChunks(KbParsedDocument document, CommonChunkConfig common) {
        List<Draft> drafts = new ArrayList<>();
        for (Span span : pack(document.text(), 0, common.maxTokens, common.overlapTokens)) {
            drafts.add(draft(document, ChunkLevel.SINGLE, null, span));
        }
        return drafts;
    }

    private List<Draft> byLine(KbParsedDocument document, ChunkConfig config) {
        int maxTokens = config.byLine.maxTokens;
        List<Unit> units = new ArrayList<>();
        StringBuilder merged = new StringBuilder();
        int mergedStart = -1;
        for (Unit line : splitLines(document.text(), 0)) {
            if (config.byLine.dropEmpty && line.text().isBlank()) {
                continue;
            }
            if (config.byLine.mergeShortLines && line.tokens() < 8 && !line.text().isBlank()) {
                if (merged.length() > 0) {
                    merged.append('\n');
                }
                if (mergedStart < 0) {
                    mergedStart = line.start();
                }
                merged.append(line.text());
                continue;
            }
            if (merged.length() > 0) {
                units.add(unit(merged.toString(), mergedStart, line.start()));
                merged.setLength(0);
                mergedStart = -1;
            }
            units.add(line);
        }
        if (merged.length() > 0) {
            units.add(unit(merged.toString(), mergedStart, document.text().length()));
        }
        List<Draft> drafts = new ArrayList<>();
        for (Span span : packUnits(units, maxTokens, 0)) {
            drafts.add(draft(document, ChunkLevel.SINGLE, null, span));
        }
        return drafts;
    }

    private List<Draft> titleEnhanced(KbParsedDocument document, ChunkConfig config) {
        List<Heading> headings = document.headings();
        if (headings.isEmpty()) {
            return defaultChunks(document, config.common);
        }
        List<Draft> drafts = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            int start = headings.get(i).offset();
            int end = i + 1 < headings.size() ? headings.get(i + 1).offset() : document.text().length();
            if (end <= start) {
                continue;
            }
            String section = document.text().substring(start, end).strip();
            if (section.isEmpty()) {
                continue;
            }
            String titlePath = document.titlePathAt(start);
            for (Span span : pack(section, start, config.common.maxTokens, config.common.overlapTokens)) {
                String content =
                        config.titleEnhanced.keepTitlePath && titlePath != null
                                ? titlePath + "\n" + span.text()
                                : span.text();
                drafts.add(
                        new Draft(
                                ChunkLevel.SINGLE,
                                null,
                                0,
                                content,
                                estimateTokens(content),
                                span.start(),
                                span.end(),
                                document.pageAt(span.start()),
                                titlePath,
                                "",
                                ""));
            }
        }
        return drafts;
    }

    private List<Draft> parentChild(KbParsedDocument document, ChunkConfig config) {
        int parentTokens = config.parentChild.parentTokens;
        int childTokens = config.parentChild.childTokens;
        int childOverlap = config.parentChild.childOverlap;
        int maxChildren = Math.max(1, config.parentChild.maxChildrenPerParent);
        List<Draft> drafts = new ArrayList<>();
        List<Span> parents = pack(document.text(), 0, parentTokens, 0);
        for (Span parent : parents) {
            String titlePath = document.titlePathAt(parent.start());
            int parentIndex = drafts.size();
            drafts.add(
                    new Draft(
                            ChunkLevel.PARENT,
                            null,
                            0,
                            parent.text(),
                            parent.tokens(),
                            parent.start(),
                            parent.end(),
                            document.pageAt(parent.start()),
                            titlePath,
                            "",
                            ""));
            List<Span> children = pack(parent.text(), parent.start(), childTokens, childOverlap);
            if (children.isEmpty()) {
                continue;
            }
            if (children.size() > maxChildren) {
                List<Span> merged = new ArrayList<>(children.subList(0, maxChildren - 1));
                Span first = children.get(maxChildren - 1);
                Span last = children.get(children.size() - 1);
                merged.add(
                        new Span(
                                document.text().substring(first.start(), last.end()),
                                estimateTokens(document.text().substring(first.start(), last.end())),
                                first.start(),
                                last.end()));
                children = merged;
            }
            for (Span child : children) {
                drafts.add(
                        new Draft(
                                ChunkLevel.CHILD,
                                parentIndex,
                                0,
                                child.text(),
                                child.tokens(),
                                child.start(),
                                child.end(),
                                document.pageAt(child.start()),
                                titlePath,
                                "",
                                ""));
            }
        }
        return drafts;
    }

    private List<Draft> custom(KbParsedDocument document, ChunkConfig config) {
        CustomConfig custom = config.custom;
        String separator = custom.separator == null ? "" : unescape(custom.separator);
        if (separator.isEmpty()) {
            return defaultChunks(document, config.common);
        }
        List<Draft> drafts = new ArrayList<>();
        String text = document.text();
        int position = 0;
        while (position <= text.length()) {
            int next = text.indexOf(separator, position);
            int end = next < 0 ? text.length() : next;
            String part = text.substring(position, end).strip();
            if (!part.isEmpty()) {
                int start = position;
                for (Span span :
                        pack(part, start, config.common.maxTokens, config.common.overlapTokens)) {
                    drafts.add(draft(document, ChunkLevel.SINGLE, null, span));
                }
            }
            if (next < 0) {
                break;
            }
            position = next + separator.length();
        }
        return drafts;
    }

    private Draft draft(KbParsedDocument document, ChunkLevel level, Integer parentIndex, Span span) {
        String titlePath = document.titlePathAt(span.start());
        return new Draft(
                level,
                parentIndex,
                0,
                span.text(),
                span.tokens(),
                span.start(),
                span.end(),
                document.pageAt(span.start()),
                titlePath,
                "",
                "");
    }

    /** 按段落/句子切分并贪心合并，超过上限的单元先硬切。 */
    private List<Span> pack(String text, int baseOffset, int maxTokens, int overlapTokens) {
        List<Unit> units = new ArrayList<>();
        for (Unit unit : splitParagraphs(text, baseOffset)) {
            if (unit.tokens() <= maxTokens * 4) {
                units.add(unit);
            } else {
                units.addAll(hardSplit(unit, maxTokens));
            }
        }
        return packUnits(units, maxTokens, overlapTokens);
    }

    private List<Span> packUnits(List<Unit> units, int maxTokens, int overlapTokens) {
        List<Span> spans = new ArrayList<>();
        int index = 0;
        while (index < units.size()) {
            int start = index;
            int tokens = 0;
            while (index < units.size()) {
                Unit unit = units.get(index);
                if (tokens > 0 && tokens + unit.tokens() > maxTokens) {
                    break;
                }
                tokens += unit.tokens();
                index++;
            }
            if (index == start) {
                index++;
            }
            Unit first = units.get(start);
            Unit last = units.get(index - 1);
            spans.add(
                    new Span(
                            joinUnits(units, start, index),
                            tokens,
                            first.start(),
                            last.end()));
            if (index >= units.size()) {
                break;
            }
            int overlapStart = index;
            int overlap = 0;
            while (overlapStart > start + 1 && overlap < overlapTokens) {
                overlapStart--;
                overlap += units.get(overlapStart).tokens();
            }
            index = Math.max(start + 1, overlapStart);
        }
        return spans;
    }

    private static String joinUnits(List<Unit> units, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(units.get(i).text());
        }
        return builder.toString();
    }

    private List<Unit> splitParagraphs(String text, int baseOffset) {
        List<Unit> units = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int next = indexOfBlankLine(text, position);
            int end = next < 0 ? text.length() : next;
            String paragraph = text.substring(position, end).strip();
            if (!paragraph.isEmpty()) {
                units.add(unit(paragraph, baseOffset + position, baseOffset + end));
            }
            if (next < 0) {
                break;
            }
            position = next;
            while (position < text.length() && (text.charAt(position) == '\n' || text.charAt(position) == '\r')) {
                position++;
            }
        }
        return units;
    }

    private static int indexOfBlankLine(String text, int from) {
        for (int i = from; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int j = i + 1;
                while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t' || text.charAt(j) == '\r')) {
                    j++;
                }
                if (j < text.length() && text.charAt(j) == '\n') {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<Unit> splitLines(String text, int baseOffset) {
        List<Unit> units = new ArrayList<>();
        int position = 0;
        for (String line : text.split("\\R", -1)) {
            int start = baseOffset + position;
            int end = start + line.length();
            if (!line.isBlank()) {
                units.add(unit(line.strip(), start, end));
            }
            position += line.length() + 1;
        }
        return units;
    }

    private List<Unit> hardSplit(Unit unit, int maxTokens) {
        List<Unit> result = new ArrayList<>();
        String text = unit.text();
        int offset = 0;
        while (offset < text.length()) {
            int end = offset;
            int tokens = 0;
            while (end < text.length() && tokens < maxTokens) {
                int codePoint = text.codePointAt(end);
                tokens += isCjk(codePoint) ? 1 : 0;
                end += Character.charCount(codePoint);
                if (!isCjk(codePoint)) {
                    tokens = (int) Math.ceil((end - offset) / 4.0);
                }
            }
            if (end == offset) {
                end = Math.min(text.length(), offset + 1);
            }
            result.add(
                    unit(
                            text.substring(offset, end),
                            unit.start() + offset,
                            unit.start() + end));
            offset = end;
        }
        return result;
    }

    private static Unit unit(String text, int start, int end) {
        return new Unit(text, estimateTokens(text), start, end);
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }

    private static String unescape(String separator) {
        return separator.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private String metadata(String strategy, String documentName, String titlePath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategy", strategy);
        if (documentName != null) {
            metadata.put("documentName", documentName);
        }
        if (titlePath != null) {
            metadata.put("titlePath", titlePath);
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new PayrollException("SHA-256 不可用");
        }
    }
}
