package io.agentscope.examples.payroll.chat;

import io.agentscope.examples.payroll.db.ChatAttachmentStore;
import io.agentscope.examples.payroll.domain.ChatAttachment;
import io.agentscope.examples.payroll.domain.ChatAttachmentRef;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.KbParserService;
import io.agentscope.examples.payroll.service.AttachmentContext;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 对话附件：落盘、文本提取与待发送生命周期；解析失败保留记录供前端展示原因。 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);
    private static final int MAX_STORED_CHARS = 2_000_000;
    private static final int PREVIEW_CHARS = 4000;
    private static final int MAX_NAME_CHARS = 180;

    private final ChatAttachmentStore store;
    private final ChatSessionManager sessions;
    private final KbParserService parser;
    private final AuditService audit;
    private final Path root;
    private final int maxFiles;
    private final long maxFileBytes;

    public ChatAttachmentService(
            ChatAttachmentStore store,
            ChatSessionManager sessions,
            KbParserService parser,
            AuditService audit,
            @Value("${payroll.chat.attachment.dir:./data/chat-uploads}") String dir,
            @Value("${payroll.chat.attachment.max-files:5}") int maxFiles,
            @Value("${payroll.chat.attachment.max-file-bytes:20971520}") long maxFileBytes) {
        this.store = store;
        this.sessions = sessions;
        this.parser = parser;
        this.audit = audit;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxFiles = Math.max(1, maxFiles);
        this.maxFileBytes = Math.max(1024L, maxFileBytes);
    }

    public List<ChatAttachmentRef> upload(
            long userId, long sessionId, List<MultipartFile> files) {
        sessions.session(userId, sessionId);
        List<MultipartFile> accepted =
                files == null
                        ? List.of()
                        : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (accepted.isEmpty()) {
            throw new PayrollException("请选择要上传的文件");
        }
        if (store.countPending(sessionId) + accepted.size() > maxFiles) {
            throw new PayrollException("单轮最多上传 " + maxFiles + " 个附件，请先发送或移除已有附件");
        }
        List<ChatAttachmentRef> results = new ArrayList<>();
        for (MultipartFile file : accepted) {
            results.add(uploadOne(userId, sessionId, file));
        }
        return List.copyOf(results);
    }

    public List<ChatAttachmentRef> pending(long userId, long sessionId) {
        sessions.session(userId, sessionId);
        return store.pending(sessionId, userId).stream().map(ChatAttachment::toRef).toList();
    }

    public AttachmentPreview preview(long userId, long sessionId, long attachmentId) {
        sessions.session(userId, sessionId);
        ChatAttachment attachment = store.require(sessionId, attachmentId);
        if (ChatAttachment.STATUS_FAILED.equals(attachment.status())) {
            throw new PayrollException(
                    attachment.error() == null ? "附件解析失败" : attachment.error());
        }
        String text = readText(attachment);
        boolean truncated = text.length() > PREVIEW_CHARS;
        return new AttachmentPreview(
                truncated ? text.substring(0, PREVIEW_CHARS) : text,
                truncated,
                attachment.textChars());
    }

    /** 发送前组装附件文本；非法、未就绪或已发送的附件直接拒绝整轮提交。 */
    public PreparedAttachments prepare(long userId, long sessionId, List<Long> attachmentIds) {
        sessions.session(userId, sessionId);
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return PreparedAttachments.empty();
        }
        List<Long> ids = new ArrayList<>();
        List<AttachmentContext.Section> sections = new ArrayList<>();
        for (Long attachmentId : new LinkedHashSet<>(attachmentIds)) {
            if (attachmentId == null) {
                continue;
            }
            ChatAttachment attachment =
                    store.find(sessionId, attachmentId)
                            .filter(item -> item.userId() == userId)
                            .orElseThrow(() -> new PayrollException("附件不存在或无权访问"));
            if (attachment.messageId() > 0) {
                throw new PayrollException("附件「" + attachment.name() + "」已发送，请勿重复提交");
            }
            if (!ChatAttachment.STATUS_READY.equals(attachment.status())) {
                throw new PayrollException(
                        "附件「"
                                + attachment.name()
                                + "」尚未就绪："
                                + (attachment.error() == null ? "解析失败" : attachment.error()));
            }
            ids.add(attachment.id());
            sections.add(
                    new AttachmentContext.Section(
                            attachment.name(),
                            attachment.ext(),
                            attachment.sizeBytes(),
                            readText(attachment)));
        }
        return new PreparedAttachments(List.copyOf(ids), AttachmentContext.format(sections));
    }

    /** 绑定失败只影响历史展示，不阻断本轮回答。 */
    public void bind(long userId, long sessionId, List<Long> attachmentIds, long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty() || messageId <= 0) {
            return;
        }
        try {
            int bound = store.bind(sessionId, userId, attachmentIds, messageId);
            if (bound != attachmentIds.size()) {
                log.warn(
                        "[附件] 绑定数量不符：sessionId={}，期望={}，实际={}",
                        sessionId,
                        attachmentIds.size(),
                        bound);
            }
            audit.record(
                    "chat.attachment.sent",
                    "chat_message",
                    Long.toString(messageId),
                    null,
                    Map.of("attachment_ids", attachmentIds));
        } catch (RuntimeException e) {
            log.warn(
                    "[附件] 绑定消息失败：sessionId={}，messageId={}，原因={}",
                    sessionId,
                    messageId,
                    e.getMessage());
        }
    }

    private String readText(ChatAttachment attachment) {
        Path textFile = resolveStorage(attachment.storageKey()).resolve("text.txt");
        try {
            return Files.isRegularFile(textFile)
                    ? Files.readString(textFile, StandardCharsets.UTF_8)
                    : "";
        } catch (IOException e) {
            throw new PayrollException("读取附件文本失败，请重新上传");
        }
    }

    public AttachmentFile download(long userId, long sessionId, long attachmentId) {
        sessions.session(userId, sessionId);
        ChatAttachment attachment = store.require(sessionId, attachmentId);
        Path file = resolveStorage(attachment.storageKey()).resolve("original." + attachment.ext());
        if (!Files.isRegularFile(file)) {
            throw new PayrollException("附件原文件已不存在");
        }
        return new AttachmentFile(file, attachment.name());
    }

    public void deletePending(long userId, long sessionId, long attachmentId) {
        sessions.session(userId, sessionId);
        ChatAttachment attachment = store.require(sessionId, attachmentId);
        if (attachment.messageId() > 0) {
            throw new PayrollException("附件已随消息发送，不能单独删除");
        }
        store.deletePending(sessionId, userId, attachmentId);
        deleteRecursively(resolveStorage(attachment.storageKey()));
        audit.record(
                "chat.attachment.deleted",
                "chat_attachment",
                Long.toString(attachmentId),
                Map.of("name", attachment.name()),
                null);
    }

    /** 会话重置或删除后清理目录；数据库记录已由 H2ChatStore 在同一事务内删除。 */
    public void deleteSessionFiles(long sessionId) {
        Path directory = root.resolve(Long.toString(sessionId)).normalize();
        if (directory.startsWith(root)) {
            deleteRecursively(directory);
        }
    }

    private ChatAttachmentRef uploadOne(long userId, long sessionId, MultipartFile file) {
        String name = sanitizeName(file.getOriginalFilename());
        String ext = extensionOf(name);
        if (!KbParserService.CHAT_SUPPORTED.contains(ext)) {
            throw new PayrollException(
                    "暂不支持「" + (ext.isEmpty() ? name : ext) + "」格式，请上传 docx/xlsx 或图片");
        }
        if (file.getSize() > maxFileBytes) {
            throw new PayrollException(
                    "附件「"
                            + name
                            + "」超过 "
                            + (maxFileBytes / 1024 / 1024)
                            + "MB，请压缩后再上传");
        }
        String storageKey = sessionId + "/" + UUID.randomUUID();
        Path directory = resolveStorage(storageKey);
        Path original = directory.resolve("original." + ext);
        ChatAttachment saved =
                store.create(sessionId, userId, name, ext, file.getSize(), storageKey);
        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
            }
            verifySignature(ext, original);
            String text = normalizeText(parser.parseChatAttachment(original, ext));
            Files.writeString(directory.resolve("text.txt"), text, StandardCharsets.UTF_8);
            store.markReady(sessionId, saved.id(), text.length());
            recordAudit(
                    "chat.attachment.uploaded",
                    saved.id(),
                    Map.of("name", name, "ext", ext, "size", file.getSize(), "chars", text.length()));
            log.info(
                    "[附件] 已解析：sessionId={}，name={}，chars={}",
                    sessionId,
                    name,
                    text.length());
        } catch (PayrollException e) {
            fail(sessionId, saved.id(), storageKey, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("[附件] 保存失败：sessionId={}，name={}，原因={}", sessionId, name, e.getMessage());
            fail(sessionId, saved.id(), storageKey, "附件保存失败，请重试");
        }
        return store.require(sessionId, saved.id()).toRef();
    }

    private void fail(long sessionId, long attachmentId, String storageKey, String message) {
        store.markFailed(sessionId, attachmentId, message);
        deleteRecursively(resolveStorage(storageKey));
    }

    private void recordAudit(String action, long attachmentId, Map<String, Object> detail) {
        try {
            audit.record(action, "chat_attachment", Long.toString(attachmentId), null, detail);
        } catch (RuntimeException e) {
            log.warn("[附件] 审计写入失败：attachmentId={}，原因={}", attachmentId, e.getMessage());
        }
    }

    private Path resolveStorage(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new PayrollException("附件路径非法");
        }
        return resolved;
    }

    /** 剥离路径与控制字符，保留可读文件名；目录名另用 UUID，杜绝路径穿越。 */
    private static String sanitizeName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length() && builder.length() < MAX_NAME_CHARS; i++) {
            char ch = name.charAt(i);
            if (ch >= 0x20 && ch != 0x7F) {
                builder.append(ch);
            }
        }
        String cleaned = builder.toString().strip();
        return cleaned.isEmpty() ? "附件" : cleaned;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,15}") ? ext : "";
    }

    /** Office 文件按魔数二次校验，防止改扩展名绕过类型限制；图片由解码阶段校验。 */
    private static void verifySignature(String ext, Path file) throws IOException {
        byte[] head = new byte[4];
        int read;
        try (InputStream input = Files.newInputStream(file)) {
            read = input.readNBytes(head, 0, head.length);
        }
        boolean valid =
                switch (ext) {
                    case "docx", "xlsx" -> read >= 2 && head[0] == 'P' && head[1] == 'K';
                    case "xls" ->
                            read >= 4
                                    && (head[0] & 0xFF) == 0xD0
                                    && (head[1] & 0xFF) == 0xCF
                                    && (head[2] & 0xFF) == 0x11
                                    && (head[3] & 0xFF) == 0xE0;
                    default -> true;
                };
        if (!valid) {
            throw new PayrollException("文件内容与扩展名不符，请上传真实的 " + ext + " 文件");
        }
    }

    private static String normalizeText(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder(Math.min(text.length(), MAX_STORED_CHARS));
        for (int i = 0; i < text.length() && builder.length() < MAX_STORED_CHARS; i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\t' || ch >= 0x20) {
                builder.append(ch);
            }
        }
        if (text.length() > MAX_STORED_CHARS) {
            builder.append("\n…（内容过长，已截断）");
        }
        String normalized = builder.toString().strip();
        if (normalized.isEmpty()) {
            throw new PayrollException("附件中没有可识别的文字");
        }
        return normalized;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            item -> {
                                try {
                                    Files.deleteIfExists(item);
                                } catch (IOException e) {
                                    log.warn("[附件] 清理文件失败：{}", item);
                                }
                            });
        } catch (IOException e) {
            log.warn("[附件] 清理目录失败：{}，原因={}", path, e.getMessage());
        }
    }

    public record AttachmentPreview(String text, boolean truncated, int chars) {
    }

    /** 本轮要发送的附件：ids 用于消息绑定，text 拼入模型输入。 */
    public record PreparedAttachments(List<Long> ids, String text) {
        public PreparedAttachments {
            ids = ids == null ? List.of() : List.copyOf(ids);
            text = text == null ? "" : text;
        }

        public static PreparedAttachments empty() {
            return new PreparedAttachments(List.of(), "");
        }

        public boolean present() {
            return !ids.isEmpty();
        }
    }

    public record AttachmentFile(Path path, String name) {
    }
}
