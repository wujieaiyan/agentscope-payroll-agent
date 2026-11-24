package io.agentscope.payroll.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.kb.parse.KbChatParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbChatParseResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.agentscope.examples.payroll.kb.parse.KbParseError;
import io.agentscope.examples.payroll.kb.parse.KbParseJobRequest;
import io.agentscope.examples.payroll.kb.parse.KbParseJobResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseSettings;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.payroll.parser.ocr.KbOcrService;
import io.agentscope.payroll.parser.ocr.OcrEngineProperties;
import io.agentscope.payroll.parser.ocr.OcrExtractionService;
import io.agentscope.payroll.parser.ocr.TesseractImageExtractor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 解析进程内部接口契约：鉴权、并发拒绝、产物复用与附件页数上限。 */
class KbParseControllerTest {

    private static final String TOKEN = "test-parse-token";
    private static final String STORAGE_KEY = "doc-1/source.pdf";

    @TempDir Path tempDir;

    @Test
    @DisplayName("缺少 PAYROLL_PARSE_TOKEN 时解析进程拒绝启动")
    void blankTokenFailsStartup() {
        KbParseSettings settings = settings(" ");
        ThreadPoolExecutor workers = workers(2);
        KbParseController controller =
                new KbParseController(mock(KbParserService.class), workers, settings, new OcrEngineProperties());

        assertThrows(IllegalStateException.class, controller::validateStartup);
        workers.shutdownNow();
    }

    @Test
    @DisplayName("签名缺失或不匹配返回 401，签名正确才进入业务校验")
    void rejectsUnsignedRequests() throws Exception {
        KbParseController controller = controller(mock(KbParserService.class));
        String body = jobBody("missing/source.pdf");
        String path = KbParseController.PARSE_PATH;

        ResponseEntity<?> missing = controller.parse(body, null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        ResponseEntity<?> forged =
                controller.parse(body, timestamp, InternalAuth.sign("other-token", "POST", path, timestamp, body));
        assertEquals(HttpStatus.UNAUTHORIZED, forged.getStatusCode());

        ResponseEntity<?> stale =
                controller.parse(
                        body,
                        Long.toString(Instant.now().getEpochSecond() - 600),
                        InternalAuth.sign(TOKEN, "POST", path, "0", body));
        assertEquals(HttpStatus.UNAUTHORIZED, stale.getStatusCode());

        ResponseEntity<?> signed =
                controller.parse(body, timestamp, InternalAuth.sign(TOKEN, "POST", path, timestamp, body));
        assertInstanceOf(KbParseError.class, signed.getBody());
        assertEquals(HttpStatus.NOT_FOUND, signed.getStatusCode());
    }

    @Test
    @DisplayName("解析成功写入产物，重复请求命中缓存且不再占用 worker")
    void cachesArtifactAcrossRequests() throws Exception {
        KbParserService parser = mock(KbParserService.class);
        KbParsedDocument document = new KbParsedDocument("工资说明", 1, List.of(), List.of());
        when(parser.parse(any(), any(), any())).thenReturn(document);
        KbParseController controller = controller(parser);
        String body = jobBody();
        String path = KbParseController.PARSE_PATH;
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(TOKEN, "POST", path, timestamp, body);

        ResponseEntity<?> first = controller.parse(body, timestamp, signature);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        KbParseJobResponse firstBody = (KbParseJobResponse) first.getBody();
        assertEquals(false, firstBody.cached());
        assertTrue(Files.isRegularFile(tempDir.resolve("artifacts").resolve(firstBody.artifactKey())));

        ResponseEntity<?> second = controller.parse(body, timestamp, signature);
        KbParseJobResponse secondBody = (KbParseJobResponse) second.getBody();
        assertEquals(true, secondBody.cached());
        assertEquals(firstBody.artifactKey(), secondBody.artifactKey());
    }

    @Test
    @DisplayName("两个 worker 占满后第三个请求直接 503，避免无界排队")
    void rejectsWhenWorkersBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        KbParserService parser = mock(KbParserService.class);
        KbParsedDocument document = new KbParsedDocument("ok", 1, List.of(), List.of());
        when(parser.parse(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            started.incrementAndGet();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return document;
                        });
        KbParseController controller = controller(parser);
        String body = jobBody();
        String path = KbParseController.PARSE_PATH;
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(TOKEN, "POST", path, timestamp, body);

        Thread first = new Thread(() -> controller.parse(body, timestamp, signature));
        Thread second = new Thread(() -> controller.parse(body, timestamp, signature));
        first.start();
        second.start();
        waitUntil(() -> started.get() == 2);

        ResponseEntity<?> third = controller.parse(body, timestamp, signature);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, third.getStatusCode());
        assertEquals("BUSY", ((KbParseError) third.getBody()).code());

        release.countDown();
        first.join(5000);
        second.join(5000);
        assertTrue(!first.isAlive() && !second.isAlive());
    }

    @Test
    @DisplayName("对话附件超过页数上限返回 400，并在错误体中给出页数与上限")
    void chatAttachmentOverPageLimitFails() throws Exception {
        KbParseController controller = controller(realParser());
        byte[] pdf = pdf(300);
        String body =
                KbParseArtifacts.mapper().writeValueAsString(
                        new KbChatParseRequest("scan.pdf", "pdf", Base64.getEncoder().encodeToString(pdf)));
        String path = KbParseController.CHAT_PARSE_PATH;
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        ResponseEntity<?> response =
                controller.parseChat(body, timestamp, InternalAuth.sign(TOKEN, "POST", path, timestamp, body));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = ((KbParseError) response.getBody()).message();
        assertTrue(message.contains("300"), message);
        assertTrue(message.contains("上限 200"), message);
    }

    @Test
    @DisplayName("带文字层的 PDF 附件经 base64 往返后返回全文与溯源")
    void chatAttachmentRoundTrip() throws Exception {
        KbParseController controller = controller(realParser());
        byte[] pdf = pdf(1);
        String body =
                KbParseArtifacts.mapper().writeValueAsString(
                        new KbChatParseRequest("clause.pdf", "pdf", Base64.getEncoder().encodeToString(pdf)));
        String path = KbParseController.CHAT_PARSE_PATH;
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        ResponseEntity<?> response =
                controller.parseChat(body, timestamp, InternalAuth.sign(TOKEN, "POST", path, timestamp, body));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        KbChatParseResponse parsed = (KbChatParseResponse) response.getBody();
        assertTrue(parsed.text().contains("annual leave"), parsed.text());
        assertEquals("native/pdf", parsed.meta().engine());
    }

    private KbParseController controller(KbParserService parser) throws IOException {
        KbParseSettings settings = settings(TOKEN);
        ThreadPoolExecutor workers = workers(2);
        KbParseController controller =
                new KbParseController(parser, workers, settings, new OcrEngineProperties());
        controller.validateStartup();
        Path source = tempDir.resolve("kb").resolve(STORAGE_KEY);
        Files.createDirectories(source.getParent());
        Files.write(source, "dummy".getBytes());
        return controller;
    }

    private KbParseSettings settings(String token) {
        KbParseSettings settings = new KbParseSettings();
        settings.setToken(token);
        settings.setKbDir(tempDir.resolve("kb").toString());
        settings.setArtifactDir(tempDir.resolve("artifacts").toString());
        settings.setMaxPages(200);
        settings.setTimeoutMs(3000);
        return settings;
    }

    private static ThreadPoolExecutor workers(int size) {
        return new ThreadPoolExecutor(
                size,
                size,
                0L,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "kb-parse-test");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static String jobBody() throws IOException {
        return jobBody(STORAGE_KEY);
    }

    private static String jobBody(String storageKey) throws IOException {
        return KbParseArtifacts.mapper()
                .writeValueAsString(
                        new KbParseJobRequest(
                                1L, storageKey, "pdf", "application/pdf", 200, ParseConfig.defaults()));
    }

    private static KbParserService realParser() {
        KbOcrService ocr = new KbOcrService(false, "tesseract", "", "chi_sim+eng", 200, 60, 0, 2, 600);
        OcrExtractionService extraction =
                new OcrExtractionService(List.of(new TesseractImageExtractor(ocr)), new OcrEngineProperties(), false);
        return new KbParserService(ocr, extraction, 200);
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("This clause describes annual leave policy page " + (i + 1) + ".");
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("条件未在超时内满足");
            }
            Thread.sleep(20);
        }
    }
}
