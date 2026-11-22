package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.ocr.OcrEngineProperties;
import io.agentscope.examples.payroll.kb.ocr.OcrExtractionService;
import io.agentscope.examples.payroll.kb.ocr.TesseractImageExtractor;
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

        KbParsedDocument parsed = parser.parse(file, "pdf", KbConfigModels.defaultParse(), null);

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

        KbParsedDocument parsed = parser.parse(file, "pdf", KbConfigModels.defaultParse(), null);

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
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null));

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
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null));

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
                        () -> parser.parse(file, "pdf", KbConfigModels.defaultParse(), null));

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
                });

        assertEquals(2, lastPage.get());
        assertEquals(2, lastOcrPages.get());
        assertEquals(2, ocr.calls.get());
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
