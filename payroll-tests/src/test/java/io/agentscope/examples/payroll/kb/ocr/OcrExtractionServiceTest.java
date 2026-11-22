package io.agentscope.examples.payroll.kb.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OcrExtractionServiceTest {

    @TempDir Path tempDir;

    private static final String GOOD_TEXT = "工资条 2026 年 11 月 应发合计 12800 元";

    @Test
    @DisplayName("首选档位未过质量门时降级到下一档并保留全部尝试记录")
    void fallsBackWhenGateRejects() throws IOException {
        FakeExtractor primary = FakeExtractor.returning("rapidocr", "ab", 0.9);
        FakeExtractor secondary = FakeExtractor.returning("tesseract", GOOD_TEXT, 0.9);
        OcrExtractionService service = service(chain("rapidocr,tesseract"), primary, secondary);

        ImageTextResult result = service.extract(image(), "chi_sim+eng");

        assertEquals("tesseract", result.engine());
        assertEquals(GOOD_TEXT, result.text());
        assertEquals(2, result.attempts().size());
        assertFalse(result.attempts().get(0).gatePassed());
        assertTrue(result.attempts().get(1).gatePassed());
        assertEquals(1, primary.calls.get());
        assertEquals(1, secondary.calls.get());
    }

    @Test
    @DisplayName("同图同参数第二次识别命中内容哈希缓存")
    void secondCallHitsCache() throws IOException {
        FakeExtractor tesseract = FakeExtractor.returning("tesseract", GOOD_TEXT, 0.9);
        OcrExtractionService service = service(chain("tesseract"), tesseract);
        Path image = image();

        ImageTextResult first = service.extract(image, "chi_sim+eng");
        ImageTextResult second = service.extract(image, "chi_sim+eng");

        assertFalse(first.cached());
        assertTrue(second.cached());
        assertEquals(0, second.latencyMs());
        assertEquals(1, tesseract.calls.get());
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断，冷却期内不再调用该档位")
    void breakerOpensAfterRepeatedFailures() throws IOException {
        FakeExtractor broken = FakeExtractor.failing("rapidocr", new IOException("进程崩溃"));
        OcrEngineProperties properties = chain("rapidocr");
        properties.setBreakerFailures(2);
        properties.setBreakerCooldownSeconds(60);
        OcrExtractionService service =
                new OcrExtractionService(List.of(broken), properties, true);
        Path image = image();

        assertThrows(OcrFailure.class, () -> service.extract(image, "chi_sim+eng"));
        assertThrows(OcrFailure.class, () -> service.extract(image, "chi_sim+eng"));
        OcrFailure third =
                assertThrows(OcrFailure.class, () -> service.extract(image, "chi_sim+eng"));

        assertEquals(2, broken.calls.get());
        assertEquals(OcrFailure.ENGINE_ERROR, third.code());
        assertEquals(1, third.attempts().size());
        assertEquals("熔断中", third.attempts().get(0).error());
    }

    @Test
    @DisplayName("所有引擎不可用时按 NO_ENGINE 报错并提示安装方式")
    void unavailableEnginesYieldNoEngine() throws IOException {
        FakeExtractor missing =
                new FakeExtractor("tesseract", false, GOOD_TEXT, 0.9, null);
        OcrExtractionService service = service(chain("tesseract"), missing);

        OcrFailure failure =
                assertThrows(OcrFailure.class, () -> service.extract(image(), "chi_sim+eng"));

        assertEquals(OcrFailure.NO_ENGINE, failure.code());
        assertTrue(failure.getMessage().contains("Tesseract"));
        assertEquals("引擎未就绪", failure.attempts().get(0).error());
    }

    @Test
    @DisplayName("识别结果全部未过质量门时按 NO_TEXT 报错")
    void gateRejectionYieldsNoText() throws IOException {
        FakeExtractor noisy = FakeExtractor.returning("tesseract", "，。？！", 0.2);
        OcrExtractionService service = service(chain("tesseract"), noisy);

        OcrFailure failure =
                assertThrows(OcrFailure.class, () -> service.extract(image(), "chi_sim+eng"));

        assertEquals(OcrFailure.NO_TEXT, failure.code());
        assertTrue(failure.getMessage().contains("质量门"));
    }

    @Test
    @DisplayName("识别结果转换为溯源元数据")
    void resultCarriesExtractionMeta() throws IOException {
        FakeExtractor tesseract = FakeExtractor.returning("tesseract", GOOD_TEXT, 0.88);
        OcrExtractionService service = service(chain("tesseract"), tesseract);

        ImageTextResult result = service.extract(image(), "chi_sim+eng");
        ExtractionMeta meta = result.toMeta(result.text().strip().length());

        assertEquals("tesseract", meta.engine());
        assertEquals("chi_sim+eng", meta.languages());
        assertEquals(GOOD_TEXT.strip().length(), meta.chars());
        assertEquals(64, meta.sourceSha256().length());
        assertTrue(meta.gatePassed());
        assertEquals(1, meta.attempts().size());
    }

    @Test
    @DisplayName("OCR 总开关关闭时直接拒绝")
    void disabledServiceRejects() throws IOException {
        FakeExtractor tesseract = FakeExtractor.returning("tesseract", GOOD_TEXT, 0.9);
        OcrExtractionService service =
                new OcrExtractionService(List.of(tesseract), chain("tesseract"), false);

        OcrFailure failure =
                assertThrows(OcrFailure.class, () -> service.extract(image(), "chi_sim+eng"));

        assertEquals(OcrFailure.NO_ENGINE, failure.code());
        assertTrue(failure.getMessage().contains("OCR 已被关闭"));
        assertFalse(service.available());
        assertEquals(0, tesseract.calls.get());
    }

    private Path image() throws IOException {
        Path file = tempDir.resolve("sample-" + System.nanoTime() + ".png");
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        return file;
    }

    private static OcrEngineProperties chain(String order) {
        OcrEngineProperties properties = new OcrEngineProperties();
        properties.setEngine("chain");
        properties.setChainOrder(order);
        return properties;
    }

    private static OcrExtractionService service(
            OcrEngineProperties properties, ImageTextExtractor... extractors) {
        return new OcrExtractionService(List.of(extractors), properties, true);
    }

    private static final class FakeExtractor implements ImageTextExtractor {
        private final String id;
        private final boolean available;
        private final String text;
        private final double confidence;
        private final Exception failure;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeExtractor(
                String id, boolean available, String text, double confidence, Exception failure) {
            this.id = id;
            this.available = available;
            this.text = text;
            this.confidence = confidence;
            this.failure = failure;
        }

        static FakeExtractor returning(String id, String text, double confidence) {
            return new FakeExtractor(id, true, text, confidence, null);
        }

        static FakeExtractor failing(String id, Exception failure) {
            return new FakeExtractor(id, true, null, 0, failure);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String model() {
            return id + "-model";
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Result extract(Path image, String languages) throws Exception {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new Result(text, confidence, 5, 10, 0);
        }
    }
}
