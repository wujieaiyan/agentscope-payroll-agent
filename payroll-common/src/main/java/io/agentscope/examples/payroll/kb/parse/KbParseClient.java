package io.agentscope.examples.payroll.kb.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.payroll.service.InternalAuth;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 远程解析客户端：固定并发 + 排队等待，503/超时按退避重试，产物走共享卷读取。 */
public class KbParseClient implements KbParserPort {

    static final String PARSE_PATH = "/internal/kb/parse";
    static final String CHAT_PARSE_PATH = "/internal/kb/chat/parse";

    private static final ObjectMapper MAPPER = KbParseArtifacts.mapper();
    private static final int MAX_ERROR_CHARS = 4096;

    private final KbParseSettings settings;
    private final Path artifactRoot;
    private final HttpClient http;
    private final Semaphore permits;
    private final String baseUrl;

    public KbParseClient(KbParseSettings settings) {
        this.settings = settings;
        if (settings.remote()) {
            if (settings.url() == null || settings.url().isBlank()) {
                throw new IllegalStateException("payroll.kb.parse.mode=remote 时必须配置解析服务地址");
            }
            if (settings.token() == null || settings.token().isBlank()) {
                throw new IllegalStateException(
                        "payroll.kb.parse.mode=remote 时必须提供 PAYROLL_PARSE_TOKEN");
            }
        }
        this.baseUrl = trimTrailingSlash(settings.url());
        this.permits = new Semaphore(Math.max(1, settings.webConcurrency()), true);
        this.artifactRoot = Path.of(settings.artifactDir()).toAbsolutePath().normalize();
        this.http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMillis(Math.max(500, settings.connectTimeoutMs())))
                        .executor(daemonPool(Math.max(2, settings.webConcurrency() + 2)))
                        .build();
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public KbParsedDocument parse(
            KbParseRequest request, KbParseListener listener, BooleanSupplier canceled) {
        long startedNanos = ParseMetrics.start();
        boolean acquired = false;
        try {
            Path file = request.file();
            if (file == null || !Files.isRegularFile(file)) {
                throw new KbParseException("源文件不存在，无法解析：" + (file == null ? "-" : file));
            }
            String sha;
            try {
                sha = KbParseArtifacts.sha256(file);
            } catch (IOException e) {
                throw new KbParseException("读取源文件失败：" + e.getMessage(), e);
            }
            String profile = KbParseArtifacts.profile(request.config(), settings.maxPages());
            KbParsedDocument cached = readCached(request.documentId(), sha, profile);
            if (cached != null) {
                ParseMetrics.duration("cached", "remote", startedNanos);
                return cached;
            }
            KbParseJobRequest payload =
                    new KbParseJobRequest(
                            request.documentId(),
                            request.storageKey(),
                            request.ext(),
                            request.mimeType(),
                            settings.maxPages(),
                            request.config());
            String body = writeJson(payload);
            acquire(canceled);
            acquired = true;
            KbParseJobResponse response = postParse(body, canceled);
            Path artifact =
                    KbParseArtifacts.resolveKey(artifactRoot, response.artifactKey());
            if (!Files.isRegularFile(artifact)) {
                throw new KbParseRetryableException("解析产物尚未可见：" + response.artifactKey());
            }
            KbParsedDocument document;
            try {
                document = KbParseArtifacts.read(artifact).document();
            } catch (IOException e) {
                throw new KbParseRetryableException("读取解析产物失败：" + e.getMessage(), e);
            }
            ParseMetrics.duration("success", engineTag(response.engine()), startedNanos);
            return document;
        } catch (RuntimeException e) {
            ParseMetrics.failure(ParseMetrics.reason(e));
            ParseMetrics.duration("failed", "remote", startedNanos);
            throw e;
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    @Override
    public ChatExtraction parseChat(Path file, String ext) {
        long startedNanos = ParseMetrics.start();
        boolean acquired = false;
        try {
            if (file == null || !Files.isRegularFile(file)) {
                throw new KbParseException("附件原文件不存在，请重新上传");
            }
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new KbParseException("读取附件失败：" + e.getMessage(), e);
            }
            String body =
                    writeJson(
                            new KbChatParseRequest(
                                    file.getFileName().toString(),
                                    ext,
                                    Base64.getEncoder().encodeToString(content)));
            acquire(null);
            acquired = true;
            KbChatParseResponse response =
                    post(CHAT_PARSE_PATH, body, KbChatParseResponse.class, null);
            ChatExtraction extraction = new ChatExtraction(response.text(), response.meta());
            ParseMetrics.duration(
                    "success",
                    engineTag(
                            extraction.meta() == null ? null : extraction.meta().engine()),
                    startedNanos);
            return extraction;
        } catch (RuntimeException e) {
            ParseMetrics.failure(ParseMetrics.reason(e));
            ParseMetrics.duration("failed", "remote", startedNanos);
            throw e;
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private static String engineTag(String engine) {
        return engine == null || engine.isBlank() ? "remote" : engine;
    }

    private KbParsedDocument readCached(long documentId, String sha, String profile) {
        Path cached = KbParseArtifacts.existing(artifactRoot, documentId, sha, profile);
        if (cached == null) {
            return null;
        }
        try {
            return KbParseArtifacts.read(cached).document();
        } catch (IOException e) {
            // 产物损坏时回源重解析，由 parser 覆盖写
            return null;
        }
    }

    private KbParseJobResponse postParse(String body, BooleanSupplier canceled) {
        return post(PARSE_PATH, body, KbParseJobResponse.class, canceled);
    }

    private <T> T post(String path, String body, Class<T> type, BooleanSupplier canceled) {
        KbParseRetryableException last = null;
        int attempts = Math.max(0, settings.maxRetries()) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCanceled(canceled);
            try {
                HttpResponse<String> response = send(path, body);
                int status = response.statusCode();
                if (status == 200) {
                    try {
                        T parsed = MAPPER.readValue(response.body(), type);
                        if (parsed == null) {
                            throw new KbParseRetryableException("解析服务返回空响应");
                        }
                        return parsed;
                    } catch (IOException e) {
                        throw new KbParseRetryableException("解析服务响应无法解析：" + e.getMessage(), e);
                    }
                }
                if (status == 401 || status == 403) {
                    throw new KbParseException("解析服务鉴权失败，请核对 PAYROLL_PARSE_TOKEN");
                }
                if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
                    last = new KbParseRetryableException("解析服务暂不可用（HTTP " + status + "）");
                } else {
                    throw new KbParseException(errorMessage(status, response.body()));
                }
            } catch (IOException e) {
                last = new KbParseRetryableException("连接解析服务失败：" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KbParseCanceled("解析请求被中断");
            }
            if (attempt < attempts) {
                sleep(settings.retryBackoffMs(), canceled);
            }
        }
        throw last == null ? new KbParseRetryableException("解析服务暂不可用") : last;
    }

    private HttpResponse<String> send(String path, String body)
            throws IOException, InterruptedException {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = InternalAuth.sign(settings.token(), "POST", path, timestamp, body);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofMillis(Math.max(1000, settings.timeoutMs() + 30_000)))
                        .header("Content-Type", "application/json")
                        .header("X-Payroll-Timestamp", timestamp)
                        .header("X-Payroll-Internal", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void acquire(BooleanSupplier canceled) {
        checkCanceled(canceled);
        try {
            if (!permits.tryAcquire(
                    Math.max(100, settings.queueWaitMs()), TimeUnit.MILLISECONDS)) {
                throw new KbParseRetryableException("解析服务繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbParseCanceled("等待解析并发许可被中断");
        }
    }

    private static void sleep(long millis, BooleanSupplier canceled) {
        long deadline = System.nanoTime() + Math.max(0, millis) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            checkCanceled(canceled);
            long remaining = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                Thread.sleep(Math.min(250, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KbParseCanceled("解析重试等待被中断");
            }
        }
    }

    private static void checkCanceled(BooleanSupplier canceled) {
        if (canceled != null && canceled.getAsBoolean()) {
            throw new KbParseCanceled("解析已取消");
        }
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new KbParseException("解析请求序列化失败：" + e.getMessage(), e);
        }
    }

    private static String errorMessage(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                KbParseError error = MAPPER.readValue(body, KbParseError.class);
                if (error.message() != null && !error.message().isBlank()) {
                    return error.message();
                }
            } catch (IOException ignored) {
                // 非 JSON 错误体退回原始文本
            }
            String text = body.strip();
            if (text.length() > MAX_ERROR_CHARS) {
                text = text.substring(0, MAX_ERROR_CHARS) + "…";
            }
            return "解析失败（HTTP " + status + "）：" + text;
        }
        return "解析失败（HTTP " + status + "）";
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static ExecutorService daemonPool(int threads) {
        AtomicInteger index = new AtomicInteger();
        return Executors.newFixedThreadPool(
                threads,
                runnable -> {
                    Thread thread =
                            new Thread(runnable, "kb-parse-http-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
