package io.agentscope.payroll.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.kb.parse.ChatExtraction;
import io.agentscope.examples.payroll.kb.parse.KbChatParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbChatParseResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseArtifacts;
import io.agentscope.examples.payroll.kb.parse.KbParseError;
import io.agentscope.examples.payroll.kb.parse.KbParseFormats;
import io.agentscope.examples.payroll.kb.parse.KbParseJobRequest;
import io.agentscope.examples.payroll.kb.parse.KbParseJobResponse;
import io.agentscope.examples.payroll.kb.parse.KbParseListener;
import io.agentscope.examples.payroll.kb.parse.KbParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbParseSettings;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.ParseConfig;
import io.agentscope.examples.payroll.kb.parse.ParseMetrics;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.payroll.parser.ocr.OcrEngineProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 解析进程内部接口：HMAC 鉴权、固定并发、产物原子落盘。 */
@RestController
public class KbParseController {

    static final String PARSE_PATH = "/internal/kb/parse";
    static final String CHAT_PARSE_PATH = "/internal/kb/chat/parse";

    private static final Logger log = LoggerFactory.getLogger(KbParseController.class);
    private static final ObjectMapper MAPPER = KbParseArtifacts.mapper();

    private final KbParserService parser;
    private final ThreadPoolExecutor workers;
    private final KbParseSettings settings;
    private final OcrEngineProperties ocrProperties;
    private final Path kbRoot;
    private final Path artifactRoot;

    public KbParseController(
            KbParserService parser,
            ThreadPoolExecutor kbParseWorkers,
            KbParseSettings settings,
            OcrEngineProperties ocrProperties) {
        this.parser = parser;
        this.workers = kbParseWorkers;
        this.settings = settings;
        this.ocrProperties = ocrProperties;
        this.kbRoot = Path.of(settings.kbDir()).toAbsolutePath().normalize();
        this.artifactRoot = Path.of(settings.artifactDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void validateStartup() {
        if (settings.token() == null || settings.token().isBlank()) {
            throw new IllegalStateException(
                    "解析进程缺少 PAYROLL_PARSE_TOKEN，内部接口拒绝启动");
        }
        try {
            Files.createDirectories(kbRoot);
            Files.createDirectories(artifactRoot);
        } catch (IOException e) {
            throw new IllegalStateException("解析目录不可用：" + e.getMessage(), e);
        }
        log.info(
                "[解析] 内部接口就绪：kbDir={}，artifactDir={}，worker={}，token=已配置",
                kbRoot,
                artifactRoot,
                settings.workerThreads());
    }

    @PostMapping(PARSE_PATH)
    public ResponseEntity<?> parse(
            @RequestBody String body,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!authorized(PARSE_PATH, timestamp, signature, body)) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "内部签名校验失败", false);
        }
        KbParseJobRequest request;
        try {
            request = MAPPER.readValue(body, KbParseJobRequest.class);
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体无法解析", false);
        }
        if (request.storageKey() == null || request.storageKey().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "缺少 storageKey", false);
        }
        Path file;
        try {
            file = resolveStorage(request.storageKey());
        } catch (PayrollException e) {
            return error(HttpStatus.BAD_REQUEST, "RESOURCE_FORBIDDEN", e.getMessage(), false);
        }
        if (file == null) {
            return error(HttpStatus.NOT_FOUND, "RESOURCE_MISSING", "源文件不存在，请重新上传文档", false);
        }
        ParseConfig config = request.config() == null ? ParseConfig.defaults() : request.config();
        int maxPages = request.maxPages() > 0 ? request.maxPages() : settings.maxPages();
        long startedNanos = ParseMetrics.start();
        String sha;
        try {
            sha = KbParseArtifacts.sha256(file);
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "READ_FAILED", "读取源文件失败：" + e.getMessage(), false);
        }
        String profile = KbParseArtifacts.profile(config, maxPages);
        Path cached = KbParseArtifacts.existing(artifactRoot, request.documentId(), sha, profile);
        if (cached != null) {
            try {
                KbParseArtifacts.Artifact artifact = KbParseArtifacts.read(cached);
                ParseMetrics.duration("cached", artifact.engine(), startedNanos);
                return ResponseEntity.ok(
                        response(artifact, artifactRoot.relativize(cached), true));
            } catch (IOException e) {
                // 产物损坏时回源重解析并覆盖
            }
        }
        AtomicInteger ocrPages = new AtomicInteger();
        KbParseListener listener =
                (pageNo, totalPages, done) -> ocrPages.set(done);
        long started = System.currentTimeMillis();
        KbParsedDocument document;
        try {
            Future<KbParsedDocument> future =
                    workers.submit(
                            () ->
                                    parser.parse(
                                            new KbParseRequest(
                                                    request.documentId(),
                                                    file,
                                                    request.storageKey(),
                                                    request.ext(),
                                                    request.mimeType(),
                                                    config),
                                            listener,
                                            () -> false));
            document = future.get(Math.max(1_000, settings.timeoutMs()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            recordFailure("503", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", "解析进程繁忙，请稍后重试", true);
        } catch (TimeoutException e) {
            recordFailure("timeout", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "TIMEOUT", "解析超时，请稍后重试", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure("canceled", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "INTERRUPTED", "解析被中断", true);
        } catch (ExecutionException e) {
            return failure(e.getCause() == null ? e : e.getCause(), startedNanos);
        } catch (PayrollException e) {
            return failure(e, startedNanos);
        }
        long elapsed = System.currentTimeMillis() - started;
        String artifactKey;
        try {
            KbParseArtifacts.Artifact artifact =
                    new KbParseArtifacts.Artifact(
                            request.documentId(),
                            sha,
                            profile,
                            request.ext(),
                            document.pageCount(),
                            document.text() == null ? 0 : document.text().length(),
                            ocrPages.get(),
                            engine(ocrPages.get()),
                            elapsed,
                            System.currentTimeMillis(),
                            document);
            Path written = KbParseArtifacts.writeAtomic(artifactRoot, artifact);
            artifactKey = artifactRoot.relativize(written).toString().replace('\\', '/');
            ParseMetrics.duration("success", engine(ocrPages.get()), startedNanos);
            return ResponseEntity.ok(
                    new KbParseJobResponse(
                            artifactKey,
                            artifact.documentId(),
                            artifact.sourceSha256(),
                            artifact.profile(),
                            artifact.pageCount(),
                            artifact.charCount(),
                            artifact.ocrPages(),
                            artifact.engine(),
                            artifact.ms(),
                            false));
        } catch (IOException e) {
            recordFailure("write", startedNanos);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "WRITE_FAILED", "解析产物落盘失败：" + e.getMessage(), true);
        }
    }

    @PostMapping(CHAT_PARSE_PATH)
    public ResponseEntity<?> parseChat(
            @RequestBody String body,
            @RequestHeader(value = "X-Payroll-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payroll-Internal", required = false) String signature) {
        if (!authorized(CHAT_PARSE_PATH, timestamp, signature, body)) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "内部签名校验失败", false);
        }
        KbChatParseRequest request;
        try {
            request = MAPPER.readValue(body, KbChatParseRequest.class);
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体无法解析", false);
        }
        String ext = request.ext() == null ? "" : request.ext().toLowerCase(Locale.ROOT);
        if (!KbParseFormats.CHAT_SUPPORTED.contains(ext)) {
            return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED", "暂不支持该附件格式", false);
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "附件内容不是合法 base64", false);
        }
        Path directory = null;
        long startedNanos = ParseMetrics.start();
        try {
            directory = Files.createTempDirectory("kb-chat-parse-");
            Path file = directory.resolve("source." + ext);
            Files.write(file, content);
            Path temp = file;
            Future<ChatExtraction> future = workers.submit(() -> parser.parseChat(temp, ext));
            ChatExtraction extraction =
                    future.get(Math.max(1_000, settings.timeoutMs()), TimeUnit.MILLISECONDS);
            ParseMetrics.duration("success", chatEngine(extraction), startedNanos);
            return ResponseEntity.ok(new KbChatParseResponse(extraction.text(), extraction.meta()));
        } catch (RejectedExecutionException e) {
            recordFailure("503", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", "解析进程繁忙，请稍后重试", true);
        } catch (TimeoutException e) {
            recordFailure("timeout", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "TIMEOUT", "解析超时，请稍后重试", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure("canceled", startedNanos);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "INTERRUPTED", "解析被中断", true);
        } catch (ExecutionException e) {
            return failure(e.getCause() == null ? e : e.getCause(), startedNanos);
        } catch (PayrollException e) {
            return failure(e, startedNanos);
        } catch (IOException e) {
            recordFailure("io", startedNanos);
            return error(HttpStatus.BAD_REQUEST, "READ_FAILED", "附件解析失败：" + e.getMessage(), false);
        } finally {
            cleanup(directory);
        }
    }

    /** 附件解析引擎：元数据缺失时按原生文本处理。 */
    private static String chatEngine(ChatExtraction extraction) {
        return extraction.meta() == null || extraction.meta().engine() == null
                ? "native"
                : extraction.meta().engine();
    }

    private ResponseEntity<KbParseError> failure(Throwable cause, long startedNanos) {
        String message =
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        log.warn("[解析] 请求失败：{}", message);
        recordFailure(reason(cause), startedNanos);
        return error(HttpStatus.BAD_REQUEST, "PARSE_FAILED", message, false);
    }

    /** 失败原因按异常类型归类，不看消息文本。 */
    private static String reason(Throwable cause) {
        return cause instanceof VirtualMachineError ? "oom" : "parse";
    }

    private static void recordFailure(String reason, long startedNanos) {
        ParseMetrics.failure(reason);
        ParseMetrics.duration("failed", "unknown", startedNanos);
    }

    private boolean authorized(String path, String timestamp, String signature, String body) {
        return InternalAuth.verify(
                settings.token(), "POST", path, timestamp, signature, body, settings.maxSkewSeconds());
    }

    private Path resolveStorage(String storageKey) {
        Path base = kbRoot;
        Path target = base.resolve(storageKey).normalize();
        if (!target.startsWith(base)) {
            throw new PayrollException("storageKey 越界：" + storageKey);
        }
        try {
            if (!Files.isRegularFile(target)) {
                return null;
            }
            Path real = target.toRealPath();
            if (!real.startsWith(base.toRealPath())) {
                throw new PayrollException("storageKey 指向知识库目录之外：" + storageKey);
            }
            return real;
        } catch (IOException e) {
            throw new PayrollException("源文件不可读：" + e.getMessage());
        }
    }

    private KbParseJobResponse response(
            KbParseArtifacts.Artifact artifact, Path relative, boolean cached) {
        return new KbParseJobResponse(
                relative.toString().replace('\\', '/'),
                artifact.documentId(),
                artifact.sourceSha256(),
                artifact.profile(),
                artifact.pageCount(),
                artifact.charCount(),
                artifact.ocrPages(),
                artifact.engine(),
                artifact.ms(),
                cached);
    }

    private String engine(int ocrPages) {
        if (ocrPages <= 0) {
            return "native";
        }
        String engine =
                ocrProperties.getEngine() == null
                        ? "chain"
                        : ocrProperties.getEngine().strip().toLowerCase(Locale.ROOT);
        return "chain".equals(engine) ? ocrProperties.getChainOrder() : engine;
    }

    private static ResponseEntity<KbParseError> error(
            HttpStatus status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).body(new KbParseError(code, message, retryable));
    }

    private static void cleanup(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 临时文件清理失败不影响响应
                                }
                            });
        } catch (IOException ignored) {
            // 目录已不存在
        }
    }
}
