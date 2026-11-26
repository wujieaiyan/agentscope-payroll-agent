package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.parse.ChatExtraction;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.payroll.parser.KbParserService;
import io.agentscope.payroll.parser.ocr.KbOcrService;
import io.agentscope.payroll.parser.ocr.OcrEngineProperties;
import io.agentscope.payroll.parser.ocr.OcrExtractionService;
import io.agentscope.payroll.parser.ocr.TesseractImageExtractor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KbParserOcrTest {

    private static final String OCR_TEXT = "第一页识别文本：Kafka 副本机制与消息可靠性说明，用于校验 OCR 兜底。";

    @TempDir Path tempDir;

    private static KbParserService parser(KbOcrService ocr) {
        OcrEngineProperties properties = new OcrEngineProperties();
        properties.setEngine("tesseract");
        properties.setChainOrder("tesseract");
        OcrExtractionService extraction =
                new OcrExtractionService(
                        List.of(new TesseractImageExtractor(ocr)), properties, ocr.enabled());
        return new KbParserService(ocr, extraction);
    }

    @Test
    @DisplayName("有文字层的 PDF 不触发 OCR")
    void textPdfSkipsOcr() throws IOException {
        Path file = tempDir.resolve("text.pdf");
        writeTextPdf(file);
        OcrStub ocr = new OcrStub(true, true, 0);
        KbParserService parser = parser(ocr);

        KbParsedDocument parsed = parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null);

        assertEquals(0, ocr.calls.get());
        assertTrue(parsed.text().contains("annual leave"));
        assertEquals(1, parsed.pageCount());
    }

    @Test
    @DisplayName("无文字层的扫描页渲染后走 OCR 并回填文本")
    void scannedPdfUsesOcrFallback() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        writeScannedPdf(file, 1);
        OcrStub ocr = new OcrStub(true, true, 0);
        KbParserService parser = parser(ocr);

        KbParsedDocument parsed = parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null);

        assertEquals(1, ocr.calls.get());
        assertTrue(parsed.text().contains("Kafka 副本机制"));
        assertEquals(1, parsed.pageCount());
        assertEquals(1, parsed.pages().size());
        assertEquals(Integer.valueOf(1), parsed.pageAt(0));
    }

    @Test
    @DisplayName("引擎缺失且整份文档无文字层时报扫描件错误")
    void scannedPdfWithoutEngineFails() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        writeScannedPdf(file, 1);
        KbParserService parser = parser(new OcrStub(true, false, 0));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null));

        assertTrue(error.getMessage().contains("扫描件"));
        assertTrue(error.getMessage().contains("Tesseract"));
    }

    @Test
    @DisplayName("关闭 OCR 时扫描件给出明确提示")
    void disabledOcrFailsForScannedPdf() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        writeScannedPdf(file, 1);
        KbParserService parser = parser(new OcrStub(false, true, 0));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null));

        assertTrue(error.getMessage().contains("OCR 已被关闭"));
    }

    @Test
    @DisplayName("扫描页数超过上限且无文字层时失败")
    void ocrPageBudgetLimitsScan() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        writeScannedPdf(file, 2);
        KbParserService parser = parser(new OcrStub(true, true, 1));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null));

        assertTrue(error.getMessage().contains("OCR 上限"));
    }

    @Test
    @DisplayName("OCR 进度回调按页上报已识别页数")
    void progressCallbackReportsOcrPages() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        writeScannedPdf(file, 2);
        OcrStub ocr = new OcrStub(true, true, 0);
        KbParserService parser = parser(ocr);
        AtomicInteger lastPage = new AtomicInteger();
        AtomicInteger lastOcrPages = new AtomicInteger();

        parser.parse(
                file,
                "pdf",
                KbConfigModels.defaultParse(),
                (pageNo, totalPages, ocrPages) -> {
                    lastPage.set(pageNo);
                    lastOcrPages.set(ocrPages);
                },
                null);

        assertEquals(2, lastPage.get());
        assertEquals(2, lastOcrPages.get());
        assertEquals(2, ocr.calls.get());
    }

    @Test
    @DisplayName("超过页数上限的 PDF 直接失败并给出页数与上限")
    void tooManyPagesFailsFast() throws IOException {
        Path file = tempDir.resolve("201.pdf");
        writePagedPdf(file, 201, false);
        KbParserService parser = parser(new OcrStub(true, true, 0));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null));

        assertTrue(error.getMessage().contains("201"));
        assertTrue(error.getMessage().contains("上限 200"));
    }

    @Test
    @DisplayName("PDF 对话附件与入库共用页数上限")
    void pdfAttachmentSharesPageLimit() throws IOException {
        Path big = tempDir.resolve("attach-201.pdf");
        writePagedPdf(big, 201, false);
        KbParserService parser = parser(new OcrStub(true, true, 0));

        PayrollException error =
                assertThrows(PayrollException.class, () -> parser.parseChat(big, "pdf"));
        assertTrue(error.getMessage().contains("201"));
        assertTrue(error.getMessage().contains("上限 200"));

        Path small = tempDir.resolve("attach-text.pdf");
        writeTextPdf(small);
        ChatExtraction extraction = parser.parseChat(small, "pdf");
        assertTrue(extraction.text().contains("annual leave"));
    }

    @Test
    @DisplayName("200 页 PDF 逐页合并、页范围连续且页眉页脚按采样剔除")
    void twoHundredPagesStreamsConsistently() throws IOException {
        Path file = tempDir.resolve("200.pdf");
        writePagedPdf(file, 200, true);
        KbParserService parser = parser(new OcrStub(true, true, 0));

        KbParsedDocument parsed = parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null);

        assertEquals(200, parsed.pageCount());
        assertEquals(200, parsed.pages().size());
        assertEquals(false, parsed.text().contains("CONFIDENTIAL HEADER"));
        assertEquals(false, parsed.text().contains("INTERNAL FOOTER"));
        for (int i = 0; i < 200; i++) {
            KbParsedDocument.PageRange range = parsed.pages().get(i);
            assertEquals(i + 1, range.pageNo());
            assertEquals("Body page " + (i + 1), parsed.text().substring(range.start(), range.end()));
            assertEquals(Integer.valueOf(i + 1), parsed.pageAt(range.start()));
            if (i > 0) {
                assertEquals(parsed.pages().get(i - 1).end() + 2, range.start());
            }
        }
    }

    private static void writeTextPdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(50, 700);
                content.showText("This clause describes annual leave policy in detail.");
                content.endText();
            }
            document.save(file.toFile());
        }
    }

    @Test
    @DisplayName("流式合并与旧全量实现在同一文档上逐页一致")
    void streamingMatchesLegacyAssembly() throws IOException {
        KbParserService parser = parser(new OcrStub(true, true, 0));
        for (boolean headerFooter : new boolean[] {true, false}) {
            Path file = tempDir.resolve("legacy-" + headerFooter + ".pdf");
            writePagedPdf(file, 12, headerFooter);

            KbParsedDocument actual =
                    parser.parse(file, "pdf", KbConfigModels.defaultParse(), null, null);
            KbParsedDocument expected = legacyParse(file);

            assertEquals(expected.text(), actual.text());
            assertEquals(expected.pageCount(), actual.pageCount());
            assertEquals(expected.pages(), actual.pages());
        }
    }

    /** 旧实现：全量页文本列表 + 频率阈值过滤 + 统一拼接，作为回归基准。 */
    private static KbParsedDocument legacyParse(Path file) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            List<String> pageTexts = new java.util.ArrayList<>(pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageTexts.add(stripper.getText(document));
            }
            if (pageCount >= 3) {
                java.util.Map<String, Integer> firstLines = new java.util.HashMap<>();
                java.util.Map<String, Integer> lastLines = new java.util.HashMap<>();
                for (String page : pageTexts) {
                    String[] lines = page.split("\\R");
                    String first = legacyNonBlank(lines, false);
                    String last = legacyNonBlank(lines, true);
                    if (first != null && first.length() <= 80) {
                        firstLines.merge(first, 1, Integer::sum);
                    }
                    if (last != null && last.length() <= 80) {
                        lastLines.merge(last, 1, Integer::sum);
                    }
                }
                int threshold = Math.max(2, (int) Math.ceil(pageCount * 0.6));
                for (int i = 0; i < pageTexts.size(); i++) {
                    String[] lines = pageTexts.get(i).split("\\R");
                    int firstIndex = legacyNonBlankIndex(lines, false);
                    int lastIndex = legacyNonBlankIndex(lines, true);
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
            StringBuilder text = new StringBuilder();
            List<KbParsedDocument.PageRange> ranges = new java.util.ArrayList<>();
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
                ranges.add(new KbParsedDocument.PageRange(i + 1, start, text.length()));
            }
            return new KbParsedDocument(text.toString(), pageCount, ranges, List.of());
        }
    }

    private static String legacyNonBlank(String[] lines, boolean fromEnd) {
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

    private static int legacyNonBlankIndex(String[] lines, boolean fromEnd) {
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

    private static void writePagedPdf(Path file, int pages, boolean headerFooter) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (headerFooter) {
                        content.beginText();
                        content.setFont(PDType1Font.HELVETICA, 10);
                        content.newLineAtOffset(50, 750);
                        content.showText("CONFIDENTIAL HEADER");
                        content.endText();
                    }
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Body page " + (i + 1));
                    content.endText();
                    if (headerFooter) {
                        content.beginText();
                        content.setFont(PDType1Font.HELVETICA, 10);
                        content.newLineAtOffset(50, 60);
                        content.showText("INTERNAL FOOTER");
                        content.endText();
                    }
                }
            }
            document.save(file.toFile());
        }
    }

    private static void writeScannedPdf(Path file, int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                BufferedImage image = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.BLACK);
                graphics.drawString("scanned page " + (i + 1), 60, 120);
                graphics.dispose();
                PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(
                            xObject, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            document.save(file.toFile());
        }
    }

    private static final class OcrStub extends KbOcrService {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean engineAvailable;

        private OcrStub(boolean enabled, boolean engineAvailable, int maxPages) {
            super(enabled, "tesseract", "", "chi_sim+eng", 200, 60, maxPages, 2, 600);
            this.engineAvailable = engineAvailable;
        }

        @Override
        public boolean available() {
            return engineAvailable;
        }

        @Override
        public KbOcrService.OcrText recognizeFile(Path image, String languages) {
            calls.incrementAndGet();
            return new KbOcrService.OcrText(OCR_TEXT, 8, 0, 0.92);
        }
    }
}
