package io.agentscope.payroll.parser;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.parse.ChatExtraction;
import io.agentscope.examples.payroll.kb.parse.KbAbortSignal;
import io.agentscope.examples.payroll.kb.parse.KbParseListener;
import io.agentscope.examples.payroll.kb.parse.KbParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument.Heading;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument.PageRange;
import io.agentscope.examples.payroll.kb.parse.KbParserPort;
import io.agentscope.examples.payroll.kb.parse.OcrConfig;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;
import io.agentscope.payroll.parser.ocr.ImageTextResult;
import io.agentscope.payroll.parser.ocr.KbOcrService;
import io.agentscope.payroll.parser.ocr.OcrCanceled;
import io.agentscope.payroll.parser.ocr.OcrExtractionService;
import io.agentscope.examples.payroll.kb.parse.OcrFailure;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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

/** 文档解析：PDF 按页、DOCX 保留标题层级、Excel 按工作表、Markdown 识别标题。 */
public class KbParserService implements KbParserPort {

    private static final Logger log = LoggerFactory.getLogger(KbParserService.class);
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    /** 页面文字层少于该字符数视为无文字页，交给 OCR 兜底。 */
    private static final int MIN_PAGE_TEXT_CHARS = 10;
    /** 已抽取但未合并的页面上限：允许 OCR 并行铺开，又避免跨页累积整份文档。 */
    private static final int MAX_PENDING_PAGES = 32;

    private final KbOcrService ocrService;
    private final OcrExtractionService extractionService;
    private final int parseMaxPages;

    public KbParserService(KbOcrService ocrService, OcrExtractionService extractionService) {
        this(ocrService, extractionService, 200);
    }

    public KbParserService(
            KbOcrService ocrService,
            OcrExtractionService extractionService,
            int parseMaxPages) {
        this.ocrService = ocrService;
        this.extractionService = extractionService;
        this.parseMaxPages = Math.max(0, parseMaxPages);
    }

    @Override
    public KbParsedDocument parse(
            KbParseRequest request, KbParseListener listener, BooleanSupplier canceled) {
        ParseConfig config = request.config() == null ? ParseConfig.defaults() : request.config();
        return parseDocument(request.file(), request.ext(), config, listener, canceled);
    }

    /** 进程内直调入口：Web inline 模式与单测复用。 */
    public KbParsedDocument parse(
            Path file,
            String ext,
            ParseConfig parseConfig,
            KbParseListener listener,
            BooleanSupplier canceled) {
        return parseDocument(file, ext, parseConfig, listener, canceled);
    }

    /** canceled 为入库取消信号：命中后停止提交新页，并中断在跑 OCR 进程。 */
    private KbParsedDocument parseDocument(
            Path file,
            String ext,
            ParseConfig parseConfig,
            KbParseListener listener,
            BooleanSupplier canceled) {
        ParseConfig config = parseConfig == null ? ParseConfig.defaults() : parseConfig;
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "pdf" -> parsePdf(file, config, listener, canceled);
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
            if (e instanceof KbAbortSignal) {
                throw e;
            }
            log.warn("[知识库] 解析失败：file={}，原因={}", file, e.getMessage());
            throw new PayrollException("解析文档失败：" + e.getMessage());
        }
    }

    /** 对话附件解析：返回纯文本与提取溯源；失败原因直接面向用户。 */
    @Override
    public ChatExtraction parseChat(Path file, String ext) {
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "pdf" -> parsePdfAttachment(file);
                case "docx", "xlsx", "xls" -> parseNativeAttachment(file, normalized);
                case "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff" -> parseImageAttachment(file);
                default -> throw new PayrollException("暂不支持该附件格式，请上传 PDF/docx/xlsx 或图片");
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
        OcrConfig ocr = ParseConfig.defaults().ocr;
        String languages = ocrService.resolveLanguages(ocr == null ? null : ocr.languages);
        ImageTextResult result = extractionService.extract(image, languages);
        String text = result.text();
        if (text == null || text.isBlank()) {
            throw new PayrollException("图片未识别出文字，请上传更清晰的截图");
        }
        return new ChatExtraction(text, result.toMeta(text.strip().length()));
    }

    /** PDF 附件与知识库共用页数上限与 OCR 兜底，解析入口保持单遍流式。 */
    private ChatExtraction parsePdfAttachment(Path file) throws IOException {
        long started = System.currentTimeMillis();
        KbParsedDocument document = parsePdf(file, ParseConfig.defaults(), null, null);
        String text = document.text();
        return new ChatExtraction(
                text,
                ExtractionMeta.nativeParse(
                        "pdf", sha256(file), System.currentTimeMillis() - started, text.length()));
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

    /** 流式摘要：上传上限 600MB，避免把整份文件读进内存后再算哈希。 */
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 逐页抽取、逐页 OCR、逐页合并：内存上限为合并文本 + 页范围表 + 有界待合并队列，
     * 不再持有全量页文本列表与合并副本。
     */
    private KbParsedDocument parsePdf(
            Path file, ParseConfig config, KbParseListener listener, BooleanSupplier canceled)
            throws IOException {
        OcrConfig ocrConfig = config.ocr == null ? new OcrConfig() : config.ocr;
        boolean filterHeaderFooter =
                config.options == null || config.options.filterHeaderFooter;
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (parseMaxPages > 0 && pageCount > parseMaxPages) {
                throw new PayrollException(
                        "文档共 "
                                + pageCount
                                + " 页，超过上限 "
                                + parseMaxPages
                                + " 页，请拆分后再入库");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            Map<Integer, String> sampled =
                    sampleBoundaryPages(document, stripper, pageCount, filterHeaderFooter);
            boolean normalizeLines = filterHeaderFooter && pageCount >= 3;
            String header = null;
            String footer = null;
            if (normalizeLines) {
                String first = firstNonBlank(sampled.get(1), false);
                String second = firstNonBlank(sampled.get(2), false);
                if (first != null && first.length() <= 80 && first.equals(second)) {
                    header = first;
                }
                String last = firstNonBlank(sampled.get(pageCount), true);
                String penultimate = firstNonBlank(sampled.get(pageCount - 1), true);
                if (last != null && last.length() <= 80 && last.equals(penultimate)) {
                    footer = last;
                }
            }
            boolean ocrEnabled = extractionService.enabled();
            boolean ocrReady = ocrEnabled && extractionService.available();
            int ocrLimit = ocrPageLimit();
            String languages = ocrReady ? ocrService.resolveLanguages(ocrConfig.languages) : null;
            BooleanSupplier abort = canceled == null ? () -> false : canceled;
            int dpi = ocrService.dpi();
            long started = System.currentTimeMillis();
            long deadline = started + ocrService.docBudgetSeconds() * 1000L;
            PDFRenderer renderer = new PDFRenderer(document);
            ExecutorService pool =
                    Executors.newFixedThreadPool(
                            Math.max(1, ocrService.concurrency()),
                            runnable -> {
                                Thread thread = new Thread(runnable, "kb-ocr-page");
                                thread.setDaemon(true);
                                return thread;
                            });
            AtomicInteger ocrPages = new AtomicInteger();
            Deque<PendingPage> pending = new ArrayDeque<>();
            StringBuilder text = new StringBuilder();
            List<PageRange> ranges = new ArrayList<>();
            long layerChars = 0;
            int blankPages = 0;
            int ocrStarted = 0;
            boolean deadlineLogged = false;
            try {
                for (int page = 1; page <= pageCount; page++) {
                    String raw =
                            sampled.containsKey(page)
                                    ? sampled.get(page)
                                    : extractPage(document, stripper, page);
                    int chars = raw.strip().length();
                    layerChars += chars;
                    Future<ImageTextResult> future = null;
                    if (chars < MIN_PAGE_TEXT_CHARS) {
                        blankPages++;
                        if (ocrReady) {
                            if (ocrLimit > 0 && ocrStarted >= ocrLimit) {
                                // 超出单文档 OCR 页数预算，保留原文字层（通常为空）
                            } else if (System.currentTimeMillis() > deadline) {
                                if (!deadlineLogged) {
                                    log.warn(
                                            "[知识库] PDF OCR 达到单文档时间上限（{}s），其余扫描页不再识别",
                                            ocrService.docBudgetSeconds());
                                    deadlineLogged = true;
                                }
                            } else {
                                ocrStarted++;
                                future = submitOcr(pool, renderer, page, dpi, languages, abort, listener, pageCount, ocrPages);
                            }
                        }
                    }
                    pending.addLast(new PendingPage(page, raw, future));
                    if (pending.size() >= MAX_PENDING_PAGES) {
                        assemblePage(pending.pollFirst(), text, ranges, header, footer, normalizeLines);
                    }
                }
                while (!pending.isEmpty()) {
                    assemblePage(pending.pollFirst(), text, ranges, header, footer, normalizeLines);
                }
            } finally {
                pool.shutdownNow();
            }
            if (ocrReady) {
                if (layerChars < MIN_PAGE_TEXT_CHARS && ocrLimit > 0 && blankPages > ocrLimit) {
                    throw new PayrollException(
                            "扫描页数 "
                                    + blankPages
                                    + " 超过 OCR 上限 "
                                    + ocrLimit
                                    + "，请调整 payroll.kb.ocr.max-pages 或拆分文档后再入库");
                }
                if (blankPages > 0) {
                    log.info(
                            "[知识库] PDF OCR：pages={}，扫描页={}，OCR 完成={}，跳过={}，耗时={}ms",
                            pageCount,
                            blankPages,
                            ocrPages.get(),
                            blankPages - ocrPages.get(),
                            System.currentTimeMillis() - started);
                }
            } else if (blankPages > 0) {
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
                        blankPages,
                        document.getDocumentInformation().getTitle());
            }
            String merged = text.toString();
            if (merged.strip().length() < MIN_PAGE_TEXT_CHARS && blankPages > 0) {
                throw new PayrollException(
                        "扫描件 OCR 未识别出文字（共 "
                                + pageCount
                                + " 页，OCR 完成 "
                                + ocrPages.get()
                                + " 页，失败 "
                                + (blankPages - ocrPages.get())
                                + " 页），请提高扫描清晰度或换用带文字层的 PDF");
            }
            return new KbParsedDocument(merged, pageCount, ranges, List.of());
        }
    }

    private Future<ImageTextResult> submitOcr(
            ExecutorService pool,
            PDFRenderer renderer,
            int pageNo,
            int dpi,
            String languages,
            BooleanSupplier abort,
            KbParseListener listener,
            int pageCount,
            AtomicInteger ocrPages) {
        return pool.submit(
                (Callable<ImageTextResult>)
                        () -> {
                            if (abort.getAsBoolean()) {
                                throw new OcrCanceled();
                            }
                            BufferedImage image;
                            synchronized (renderer) {
                                image =
                                        renderer.renderImageWithDPI(
                                                pageNo - 1, dpi, ImageType.RGB);
                            }
                            ImageTextResult result;
                            try {
                                result = extractionService.extract(image, languages, abort);
                            } finally {
                                image.flush();
                            }
                            int done = ocrPages.incrementAndGet();
                            if (listener != null) {
                                // 并行识别下页码不再单调，进度按已完成页数上报。
                                synchronized (listener) {
                                    listener.onPageProgress(done, pageCount, done);
                                }
                            }
                            return result;
                        });
    }

    /** 取出队首页面的 OCR 结果（若有）并合并；失败页回退文字层，取消信号原样上抛。 */
    private static void assemblePage(
            PendingPage page,
            StringBuilder text,
            List<PageRange> ranges,
            String header,
            String footer,
            boolean normalizeLines)
            throws IOException {
        String content = awaitPage(page);
        if (normalizeLines) {
            String[] lines = content.split("\\R");
            int firstIndex = firstNonBlankIndex(lines, false);
            int lastIndex = firstNonBlankIndex(lines, true);
            if (firstIndex >= 0 && firstIndex != lastIndex) {
                if (header != null && header.equals(lines[firstIndex].strip())) {
                    lines[firstIndex] = "";
                }
                if (footer != null && footer.equals(lines[lastIndex].strip())) {
                    lines[lastIndex] = "";
                }
            }
            content = String.join("\n", lines);
        }
        String stripped = content.strip();
        if (stripped.isEmpty()) {
            return;
        }
        int start = text.length();
        if (start > 0) {
            text.append("\n\n");
            start += 2;
        }
        text.append(stripped);
        ranges.add(new PageRange(page.pageNo(), start, text.length()));
    }

    private static String awaitPage(PendingPage page) throws IOException {
        if (page.ocr() == null) {
            return page.rawText();
        }
        try {
            ImageTextResult result = page.ocr().get();
            return result.text() == null ? "" : result.text();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR 被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 取消信号向上传递；单页识别失败与旧串行实现一致，跳过该页继续。
            if (cause instanceof KbAbortSignal && cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            log.warn("[知识库] OCR 页失败：{}", cause.getMessage());
            return page.rawText();
        }
    }

    private static String extractPage(
            PDDocument document, PDFTextStripper stripper, int page) throws IOException {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }

    /** 首页两页与末页两页采样，供流式页眉页脚判定；不足 3 页时旧实现本就不过滤。 */
    private static Map<Integer, String> sampleBoundaryPages(
            PDDocument document, PDFTextStripper stripper, int pageCount, boolean filter)
            throws IOException {
        if (!filter || pageCount < 3) {
            return Map.of();
        }
        Map<Integer, String> sampled = new HashMap<>(4);
        for (int page : new int[] {1, 2, pageCount - 1, pageCount}) {
            if (!sampled.containsKey(page)) {
                sampled.put(page, extractPage(document, stripper, page));
            }
        }
        return sampled;
    }

    /** OCR 单文档页数上限：解析上限与 OCR 上限取小，0 表示不限。 */
    private int ocrPageLimit() {
        int ocrLimit = ocrService.maxPages();
        if (ocrLimit <= 0) {
            return parseMaxPages;
        }
        return parseMaxPages <= 0 ? ocrLimit : Math.min(parseMaxPages, ocrLimit);
    }

    private record PendingPage(int pageNo, String rawText, Future<ImageTextResult> ocr) {
    }

    private static String firstNonBlank(String text, boolean fromEnd) {
        return text == null ? null : firstNonBlank(text.split("\\R"), fromEnd);
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
