package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbParsedDocument.Heading;
import io.agentscope.examples.payroll.kb.KbParsedDocument.PageRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 文档解析：PDF 按页、DOCX 保留标题层级、Excel 按工作表、Markdown 识别标题。 */
@Service
public class KbParserService {

    private static final Logger log = LoggerFactory.getLogger(KbParserService.class);
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    public static final List<String> SUPPORTED = List.of("pdf", "docx", "xlsx", "xls", "md", "markdown");

    public KbParsedDocument parse(Path file, String ext) {
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "pdf" -> parsePdf(file);
                case "docx" -> parseDocx(file);
                case "xlsx", "xls" -> parseExcel(file);
                case "md", "markdown" -> parseMarkdown(file);
                default -> throw new PayrollException("暂不支持的文档类型：" + ext);
            };
        } catch (PayrollException e) {
            throw e;
        } catch (IOException e) {
            throw new PayrollException("解析文档失败：" + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[知识库] 解析失败：file={}，原因={}", file, e.getMessage());
            throw new PayrollException("解析文档失败：" + e.getMessage());
        }
    }

    private KbParsedDocument parsePdf(Path file) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            List<String> pageTexts = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageTexts.add(stripper.getText(document));
            }
            filterHeaderFooter(pageTexts);
            StringBuilder text = new StringBuilder();
            List<PageRange> ranges = new ArrayList<>();
            for (int i = 0; i < pageTexts.size(); i++) {
                String content = pageTexts.get(i).strip();
                if (content.isEmpty()) {
                    continue;
                }
                int start = text.length();
                if (start > 0) {
                    text.append("\n\n");
                    start += 2;
                }
                text.append(content);
                ranges.add(new PageRange(i + 1, start, text.length()));
            }
            return new KbParsedDocument(text.toString(), pageCount, ranges, List.of());
        }
    }

    /** 连续出现且长度较短的页眉页脚行在 3 页以上时剔除。 */
    private void filterHeaderFooter(List<String> pageTexts) {
        if (pageTexts.size() < 3) {
            return;
        }
        Map<String, Integer> firstLines = new HashMap<>();
        Map<String, Integer> lastLines = new HashMap<>();
        for (String page : pageTexts) {
            String[] lines = page.split("\\R");
            String first = firstNonBlank(lines, false);
            String last = firstNonBlank(lines, true);
            if (first != null && first.length() <= 80) {
                firstLines.merge(first, 1, Integer::sum);
            }
            if (last != null && last.length() <= 80) {
                lastLines.merge(last, 1, Integer::sum);
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pageTexts.size() * 0.6));
        for (int i = 0; i < pageTexts.size(); i++) {
            String[] lines = pageTexts.get(i).split("\\R");
            int firstIndex = firstNonBlankIndex(lines, false);
            int lastIndex = firstNonBlankIndex(lines, true);
            if (firstIndex >= 0
                    && firstIndex != lastIndex
                    && firstLines.getOrDefault(lines[firstIndex].strip(), 0) >= threshold) {
                lines[firstIndex] = "";
            }
            if (lastIndex >= 0
                    && firstIndex != lastIndex
                    && lastLines.getOrDefault(lines[lastIndex].strip(), 0) >= threshold) {
                lines[lastIndex] = "";
            }
            pageTexts.set(i, String.join("\n", lines));
        }
    }

    private static String firstNonBlank(String[] lines, boolean fromEnd) {
        if (fromEnd) {
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) {
                    return lines[i].strip();
                }
            }
            return null;
        }
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return null;
    }

    private static int firstNonBlankIndex(String[] lines, boolean fromEnd) {
        if (fromEnd) {
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private KbParsedDocument parseDocx(Path file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            StringBuilder text = new StringBuilder();
            List<Heading> headings = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String content = paragraph.getText() == null ? "" : paragraph.getText().strip();
                    if (content.isEmpty()) {
                        continue;
                    }
                    int level = headingLevel(paragraph);
                    if (level > 0) {
                        if (text.length() > 0) {
                            text.append("\n\n");
                        }
                        headings.add(new Heading(text.length(), level, content));
                        text.append("#".repeat(Math.min(level, 6))).append(' ').append(content);
                    } else {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(content);
                    }
                } else if (element instanceof XWPFTable table) {
                    String rendered = renderTable(table);
                    if (!rendered.isBlank()) {
                        if (text.length() > 0) {
                            text.append("\n\n");
                        }
                        text.append(rendered);
                    }
                }
            }
            String content = text.toString().strip();
            List<PageRange> pages =
                    content.isEmpty() ? List.of() : List.of(new PageRange(1, 0, content.length()));
            return new KbParsedDocument(content, content.isEmpty() ? 0 : 1, pages, headings);
        }
    }

    private static int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null) {
            String normalized = style.replace(" ", "").toLowerCase(Locale.ROOT);
            if (normalized.startsWith("heading") || normalized.startsWith("标题")) {
                for (int i = 0; i < normalized.length(); i++) {
                    if (Character.isDigit(normalized.charAt(i))) {
                        return Math.min(6, normalized.charAt(i) - '0');
                    }
                }
                return 1;
            }
        }
        return 0;
    }

    private static String renderTable(XWPFTable table) {
        StringBuilder builder = new StringBuilder();
        int previousRows = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = cell.getText() == null ? "" : cell.getText().replaceAll("\\s+", " ").strip();
                cells.add(value);
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (previousRows > 0) {
                builder.append('\n');
            }
            builder.append(String.join(" | ", cells));
            previousRows++;
        }
        return builder.toString();
    }

    private KbParsedDocument parseExcel(Path file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder text = new StringBuilder();
            List<PageRange> pages = new ArrayList<>();
            List<Heading> headings = new ArrayList<>();
            int pageNo = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                StringBuilder sheetText = new StringBuilder();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).strip();
                        cells.add(value);
                    }
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                        cells.remove(cells.size() - 1);
                    }
                    if (cells.stream().allMatch(String::isEmpty)) {
                        continue;
                    }
                    if (sheetText.length() > 0) {
                        sheetText.append('\n');
                    }
                    sheetText.append(String.join(" | ", cells));
                }
                if (sheetText.length() == 0) {
                    continue;
                }
                pageNo++;
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                String sheetName = sheet.getSheetName() == null ? "Sheet" + pageNo : sheet.getSheetName();
                headings.add(new Heading(text.length(), 1, sheetName));
                text.append("# ").append(sheetName).append('\n');
                int start = text.length();
                text.append(sheetText);
                pages.add(new PageRange(pageNo, start, text.length()));
            }
            String content = text.toString().strip();
            return new KbParsedDocument(content, pageNo, pages, headings);
        }
    }

    private KbParsedDocument parseMarkdown(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        List<Heading> headings = new ArrayList<>();
        boolean inFence = false;
        for (String line : raw.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                inFence = !inFence;
                text.append(line).append('\n');
                continue;
            }
            Matcher matcher = inFence ? null : MD_HEADING.matcher(stripped);
            if (matcher != null && matcher.matches()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                int level = matcher.group(1).length();
                String title = matcher.group(2).strip();
                headings.add(new Heading(text.length(), level, title));
            }
            text.append(line).append('\n');
        }
        String content = text.toString().strip();
        List<PageRange> pages =
                content.isEmpty() ? List.of() : List.of(new PageRange(1, 0, content.length()));
        return new KbParsedDocument(content, content.isEmpty() ? 0 : 1, pages, headings);
    }
}
