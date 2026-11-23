package io.agentscope.examples.payroll.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.ChatAttachmentStore;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.H2ChatStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.ChatAttachment;
import io.agentscope.examples.payroll.domain.ChatAttachmentRef;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbOcrService;
import io.agentscope.examples.payroll.kb.KbParserService;
import io.agentscope.examples.payroll.kb.ocr.OcrEngineProperties;
import io.agentscope.examples.payroll.kb.ocr.OcrExtractionService;
import io.agentscope.examples.payroll.kb.ocr.TesseractImageExtractor;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ChatAttachmentServiceTest {

    private static final long USER_ID = 7L;

    @TempDir Path tempDir;

    private H2ChatStore chatStore;
    private ChatAttachmentStore store;
    private ChatAttachmentService service;
    private AttachmentParseQueue queue;
    private long sessionId;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("chat-attachment-" + System.nanoTime());
        chatStore = new H2ChatStore(database);
        store = new ChatAttachmentStore(database);
        sessionId = chatStore.create(USER_ID, "附件会话").id();
        ChatSessionManager manager = new ChatSessionManager(chatStore, (session, user) -> null);
        KbOcrService ocr = new KbOcrService(false, "tesseract", "", "chi_sim+eng", 200, 60, 0, 2, 600);
        KbParserService parser =
                new KbParserService(
                        ocr,
                        new OcrExtractionService(
                                List.of(new TesseractImageExtractor(ocr)),
                                new OcrEngineProperties(),
                                false));
        queue = new AttachmentParseQueue(store, 1, 10);
        service =
                new ChatAttachmentService(
                        store,
                        manager,
                        parser,
                        queue,
                        new AuditService(new H2AuditStore(database), 30),
                        tempDir.toString(),
                        2,
                        5 * 1024 * 1024);
    }

    private void awaitParse() throws InterruptedException {
        assertTrue(queue.awaitIdle(5000), "附件解析任务未在超时内完成");
    }

    @Test
    @DisplayName("docx 上传后解析为文本，发送前可组装资料块并绑定消息")
    void uploadDocxParsesAndPrepares() throws Exception {
        ChatAttachmentRef uploaded =
                service.upload(
                                USER_ID,
                                sessionId,
                                List.of(
                                        new MockMultipartFile(
                                                "files",
                                                "十月工资说明.docx",
                                                "application/vnd.openxmlformats-officedocument"
                                                        + ".wordprocessingml.document",
                                                docx("2026 年 10 月工资说明", "缺勤扣款按日工资计算"))))
                        .get(0);
        assertEquals(ChatAttachment.STATUS_PARSING, uploaded.status());

        awaitParse();
        ChatAttachmentRef ready = service.pending(USER_ID, sessionId).get(0);
        assertEquals(ChatAttachment.STATUS_READY, ready.status());
        assertTrue(ready.textChars() > 0);
        assertEquals(100, ready.progress());
        assertTrue(ready.extractionMeta().engine().startsWith("native/"));
        assertTrue(ready.extractionMeta().sourceSha256() != null);
        assertEquals(1, service.pending(USER_ID, sessionId).size());

        ChatAttachmentService.PreparedAttachments prepared =
                service.prepare(USER_ID, sessionId, List.of(uploaded.id()));
        assertEquals(List.of(uploaded.id()), prepared.ids());
        assertTrue(prepared.text().contains("[附件资料]"));
        assertTrue(prepared.text().contains("十月工资说明.docx"));
        assertTrue(prepared.text().contains("缺勤扣款按日工资计算"));

        service.bind(USER_ID, sessionId, prepared.ids(), 99L);
        assertEquals(1, store.refs(sessionId, 99L).size());
        assertEquals(0, service.pending(USER_ID, sessionId).size());

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.prepare(USER_ID, sessionId, List.of(uploaded.id())));
        assertTrue(error.getMessage().contains("已发送"));
    }

    @Test
    @DisplayName("重新识别会回到解析中并保留原文件")
    void reparseRunsAgainFromOriginal() throws Exception {
        ChatAttachmentRef uploaded =
                service.upload(
                                USER_ID,
                                sessionId,
                                List.of(
                                        new MockMultipartFile(
                                                "files",
                                                "说明.docx",
                                                "application/octet-stream",
                                                docx("工资说明文本"))))
                        .get(0);
        awaitParse();
        int chars = service.pending(USER_ID, sessionId).get(0).textChars();

        ChatAttachmentRef reparsed = service.reparse(USER_ID, sessionId, uploaded.id());
        // 队列工作线程可能已推进到 READY，这里只要求不处于失败态。
        assertTrue(
                ChatAttachment.STATUS_PARSING.equals(reparsed.status())
                        || ChatAttachment.STATUS_READY.equals(reparsed.status()));
        if (ChatAttachment.STATUS_PARSING.equals(reparsed.status())) {
            assertTrue(reparsed.progress() < 100);
        }

        awaitParse();
        ChatAttachmentRef ready = service.pending(USER_ID, sessionId).get(0);
        assertEquals(ChatAttachment.STATUS_READY, ready.status());
        assertEquals(chars, ready.textChars());
    }

    @Test
    @DisplayName("不支持的扩展名直接拒绝上传")
    void uploadRejectsUnsupportedExtension() {
        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () ->
                                service.upload(
                                        USER_ID,
                                        sessionId,
                                        List.of(
                                                new MockMultipartFile(
                                                        "files",
                                                        "合同.pdf",
                                                        "application/pdf",
                                                        "%PDF-1.4".getBytes(
                                                                StandardCharsets.UTF_8)))));

        assertTrue(error.getMessage().contains("暂不支持"));
    }

    @Test
    @DisplayName("改扩展名伪装成 docx 的文件按解析失败保留原因")
    void disguisedDocxFailsWithReason() {
        ChatAttachmentRef failed =
                service.upload(
                                USER_ID,
                                sessionId,
                                List.of(
                                        new MockMultipartFile(
                                                "files",
                                                "假的.docx",
                                                "application/octet-stream",
                                                "not a zip".getBytes(StandardCharsets.UTF_8))))
                        .get(0);

        assertEquals(ChatAttachment.STATUS_FAILED, failed.status());
        assertTrue(failed.error().contains("扩展名"));

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () -> service.prepare(USER_ID, sessionId, List.of(failed.id())));
        assertTrue(error.getMessage().contains("尚未就绪"));
    }

    @Test
    @DisplayName("其他用户不能读取或发送本会话的附件")
    void otherUserCannotAccessAttachments() throws Exception {
        service.upload(
                USER_ID,
                sessionId,
                List.of(
                        new MockMultipartFile(
                                "files",
                                "说明.docx",
                                "application/octet-stream",
                                docx("工资说明"))));
        awaitParse();

        assertThrows(PayrollException.class, () -> service.pending(8L, sessionId));
    }

    private static byte[] docx(String... paragraphs) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(text);
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
