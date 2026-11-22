package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbConfigModels.OcrConfig;
import io.agentscope.examples.payroll.kb.KbConfigModels.ParseConfig;
import io.agentscope.examples.payroll.kb.KbParsedDocument.Heading;
import io.agentscope.examples.payroll.kb.KbParsedDocument.PageRange;
import io.agentscope.examples.payroll.kb.ocr.ImageTextResult;
import io.agentscope.examples.payroll.kb.ocr.OcrExtractionService;
import io.agentscope.examples.payroll.kb.ocr.OcrFailure;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
    /** 页面文字层少于该字符数视为无文字页，交给 OCR 兜底。 */
    private static final int MIN_PAGE_TEXT_CHARS = 10;

    public static final List<String> SUPPORTED = List.of("pdf", "docx", "xlsx", "xls", "md", "markdown");

    /** 对话附件支持的格式：Word / Excel / 图片（图片走 Tesseract OCR）。 */
    public static final List<String> CHAT_SUPPORTED =
            List.of("docx", "xlsx", "xls", "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff");

    /** 解析进度回调，仅扫描页 OCR 阶段触发；抛出的异常会原样向上传递。 */
    @FunctionalInterface
    public interface PageListener {
        void onPageProgress(int pageNo, int totalPages, int ocrPages);
    }

    /** 进度回调抛出的控制流信号（如取消），解析层不包装成解析失败。 */
    public interface AbortSignal {
    }

    private final KbOcrService ocrService;
    private final OcrExtractionService extractionService;

    public KbParserService(KbOcrService ocrService, OcrExtractionService extractionService) {
        this.ocrService = ocrService;
        this.extractionService = extractionService;
    }

    public KbParsedDocument parse(Path file, String ext) {
        return parse(file, ext, KbConfigModels.defaultParse(), null);
    }

    public KbParsedDocument parse(
            Path file, String ext, ParseConfig parseConfig, PageListener listener) {
        ParseConfig config = parseConfig == null ? KbConfigModels.defaultParse() : parseConfig;
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "pdf" -> parsePdf(file, config, listener);
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
            if (e instanceof AbortSignal) {
                throw e;
            }
            log.warn("[知识库] 解析失败：file={}，原因={}", file, e.getMessage());
            throw new PayrollException("解析文档失败：" + e.getMessage());
        }
    }

    /** 对话附件解析：返回纯文本与提取溯源；失败原因直接面向用户。 */
    public ChatExtraction parseChatAttachment(Path file, String ext) {
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "docx", "xlsx", "xls" -> parseNativeAttachment(file, normalized);
                case "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff" -> parseImageAttachment(file);
                default -> throw new PayrollException("暂不支持该附件格式，请上传 docx/xlsx 或图片");
            };
        } catch (PayrollException | OcrFailure e) {
            throw e instanceof PayrollException error ? error : new PayrollException(e.getMessage());
        } catch (IOException e) {
            throw new PayrollException("解析附件失败：" + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[附件] 解析失败：file={}，原因={}", file, e.getMessage());
            throw new PayrollException("解析附件失败：" + e.getMessage());
        }
    }

    /** 图片附件走档位链 OCR，返回实际生效的引擎与质量门结论。 */
    private ChatExtraction parseImageAttachment(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new PayrollException("无法解码图片，请上传 PNG/JPG 等常见格式");
        }
        OcrConfig ocr = KbConfigModels.defaultParse().ocr;
        String languages = ocrService.resolveLanguages(ocr == null ? null : ocr.languages);
        ImageTextResult result = extractionService.extract(image, languages);
        String text = result.text();
        if (text == null || text.isBlank()) {
            throw new PayrollException("图片未识别出文字，请上传更清晰的截图");
        }
        return new ChatExtraction(text, result.toMeta(text.strip().length()));
    }

    /** Word/Excel 原生解析不经过 OCR 引擎链，只记录类型、耗时与原文哈希。 */
    private ChatExtraction parseNativeAttachment(Path file, String ext) throws IOException {
        long started = System.currentTimeMillis();
        KbParsedDocument document =
                switch (ext) {
                    case "docx" -> parseDocx(file);
                    case "xlsx", "xls" -> parseExcel(file);
                    default -> throw new PayrollException("暂不支持的文档类型：" + ext);
                };
        String text = document.text();
        return new ChatExtraction(
                text,
                ExtractionMeta.nativeParse(
                        ext, sha256(file), System.currentTimeMillis() - started, text.length()));
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private KbParsedDocument parsePdf(Path file, ParseConfig config, PageListener listener)
            throws IOException {
        OcrConfig ocrConfig = config.ocr == null ? new OcrConfig() : config.ocr;
        boolean filterHeaderFooter =
                config.options == null || config.options.filterHeaderFooter;
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            List<String> pageTexts = new ArrayList<>(pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageTexts.add(stripper.getText(document));
            }
            OcrStats ocrStats = ocrBlankPages(document, pageTexts, ocrConfig, listener);
            if (filterHeaderFooter) {
                filterHeaderFooter(pageTexts);
            }
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
            String merged = text.toString();
            if (merged.strip().length() < MIN_PAGE_TEXT_CHARS && ocrStats.blankPages() > 0) {
                throw new PayrollException(
                        "扫描件 OCR 未识别出文字（共 "
                                + pageCount
                                + " 页，OCR 完成 "
                                + ocrStats.ocrPages()
                                + " 页，失败 "
                                + ocrStats.skippedPages()
                                + " 页），请提高扫描清晰度或换用带文字层的 PDF");
            }
            return new KbParsedDocument(merged, pageCount, ranges, List.of());
        }
    }

    /** 无文字层的页面渲染后交给 OCR；引擎缺失时只在整份文档都不可读时失败。 */
    private OcrStats ocrBlankPages(
            PDDocument document,
            List<String> pageTexts,
            OcrConfig ocrConfig,
            PageListener listener)
            throws IOException {
        List<Integer> blankPages = new ArrayList<>();
        long layerChars = 0;
        for (int i = 0; i < pageTexts.size(); i++) {
            int chars = pageTexts.get(i).strip().length();
            layerChars += chars;
            if (chars < MIN_PAGE_TEXT_CHARS) {
                blankPages.add(i);
            }
        }
        if (blankPages.isEmpty()) {
            return new OcrStats(0, 0, 0);
        }
        boolean ocrEnabled = extractionService.enabled();
        if (!ocrEnabled || !extractionService.available()) {
            if (layerChars < MIN_PAGE_TEXT_CHARS) {
                throw new PayrollException(
                        ocrEnabled
                                ? "该 PDF 为扫描件（无文字层），但未找到可用的 OCR 引擎"
                                        + "（Tesseract/RapidOCR）。请安装 Tesseract（含 chi_sim 语言包），"
                                        + "或用 payroll.kb.ocr.command 指定可执行文件，也可改用带文字层的 PDF"
                                : "该 PDF 为扫描件（无文字层），OCR 已被关闭"
                                        + "（payroll.kb.ocr.enabled=false），请启用 OCR 或改用带文字层的 PDF");
            }
            log.warn(
                    "[知识库] 有 {} 页扫描页未做 OCR（引擎不可用/已关闭），按现有文字层继续解析：{}",
                    blankPages.size(),
                    document.getDocumentInformation().getTitle());
            return new OcrStats(0, 0, blankPages.size());
        }
        int budget = blankPages.size();
        int maxPages = ocrService.maxPages();
        if (maxPages > 0 && blankPages.size() > maxPages) {
            if (layerChars < MIN_PAGE_TEXT_CHARS) {
                throw new PayrollException(
                        "扫描页数 "
                                + blankPages.size()
                                + " 超过 OCR 上限 "
                                + maxPages
                                + "，请调整 payroll.kb.ocr.max-pages 或拆分文档后再入库");
            }
            budget = maxPages;
        }
        String languages = ocrService.resolveLanguages(ocrConfig.languages);
        PDFRenderer renderer = new PDFRenderer(document);
        long started = System.currentTimeMillis();
        int ocrPages = 0;
        int skipped = blankPages.size() - budget;
        for (int index = 0; index < budget; index++) {
            int pageIndex = blankPages.get(index);
            try {
                BufferedImage image =
                        renderer.renderImageWithDPI(pageIndex, ocrService.dpi(), ImageType.RGB);
                ImageTextResult result = extractionService.extract(image, languages);
                pageTexts.set(pageIndex, result.text() == null ? "" : result.text());
                ocrPages++;
            } catch (IOException | RuntimeException e) {
                skipped++;
                log.warn("[知识库] 第 {} 页 OCR 失败：{}", pageIndex + 1, e.getMessage());
            }
            if (listener != null) {
                listener.onPageProgress(pageIndex + 1, pageTexts.size(), ocrPages);
            }
        }
        log.info(
                "[知识库] PDF OCR：pages={}，扫描页={}，OCR 完成={}，跳过={}，耗时={}ms",
                pageTexts.size(),
                blankPages.size(),
                ocrPages,
                skipped,
                System.currentTimeMillis() - started);
        return new OcrStats(blankPages.size(), ocrPages, skipped);
    }

    private record OcrStats(int blankPages, int ocrPages, int skippedPages) {
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

    /** 对话附件解析结果：文本 + 提取溯源，供落库与前端展示引擎信息。 */
    public record ChatExtraction(String text, ExtractionMeta meta) {
    }
}
