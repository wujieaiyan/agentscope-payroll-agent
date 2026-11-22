package io.agentscope.examples.payroll.kb.ocr;

import io.agentscope.examples.payroll.domain.ExtractionMeta;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 档位链：按配置顺序尝试识别，质量门决定是否接受，失败按 NO_ENGINE / NO_TEXT / ENGINE_ERROR 分类。
 *
 * <p>每档独立超时与熔断；同图同参数命中内容哈希缓存，不重复推理。
 */
@Service
public class OcrExtractionService {

    private static final Logger log = LoggerFactory.getLogger(OcrExtractionService.class);

    private final boolean enabled;
    private final OcrEngineProperties properties;
    private final Map<String, ImageTextExtractor> extractors;
    private final List<String> allEngines;
    private final OcrQualityGate gate;
    private final Map<String, ImageTextExtractor.Result> cache;
    private final Map<String, Breaker> breakers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService timeoutExecutor;

    public OcrExtractionService(
            List<ImageTextExtractor> extractors,
            OcrEngineProperties properties,
            @Value("${payroll.kb.ocr.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.properties = properties;
        Map<String, ImageTextExtractor> byId = new LinkedHashMap<>();
        for (ImageTextExtractor extractor : extractors) {
            byId.put(extractor.id().toLowerCase(Locale.ROOT), extractor);
        }
        this.extractors = Collections.unmodifiableMap(byId);
        this.allEngines = List.copyOf(byId.keySet());
        OcrEngineProperties.QualityGate config = properties.getQualityGate();
        this.gate =
                new OcrQualityGate(
                        config.getMinChars(),
                        config.getMinAlnumRatio(),
                        config.getMinCjkRatio(),
                        config.getMinConfidence());
        this.cache = newCache(Math.max(0, properties.getCacheSize()));
        this.timeoutExecutor =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "ocr-timeout");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public boolean enabled() {
        return enabled;
    }

    /** 至少一档可用即视为 OCR 可用；只做依赖探测，不加载模型。 */
    public boolean available() {
        if (!enabled) {
            return false;
        }
        for (String engineId : engineOrder()) {
            ImageTextExtractor extractor = extractors.get(engineId);
            if (extractor != null && extractor.available()) {
                return true;
            }
        }
        return false;
    }

    public List<String> engineOrder() {
        if (!enabled) {
            return List.of();
        }
        String engine =
                properties.getEngine() == null
                        ? "chain"
                        : properties.getEngine().strip().toLowerCase(Locale.ROOT);
        if (!"chain".equals(engine)) {
            return List.of(engine);
        }
        Set<String> order = new LinkedHashSet<>();
        String configured =
                properties.getChainOrder() == null ? "rapidocr,tesseract" : properties.getChainOrder();
        for (String item : configured.split(",")) {
            String id = item.strip().toLowerCase(Locale.ROOT);
            if (!id.isEmpty() && extractors.containsKey(id)) {
                order.add(id);
            }
        }
        if (order.isEmpty()) {
            order.addAll(allEngines);
        }
        return List.copyOf(order);
    }

    public ImageTextResult extract(BufferedImage image, String languages) throws IOException {
        Path directory = Files.createTempDirectory("ocr-image-");
        try {
            Path png = directory.resolve("source.png");
            if (!ImageIO.write(image, "png", png.toFile())) {
                throw new IOException("无法写出 OCR 临时图片");
            }
            return extract(png, languages);
        } finally {
            deleteQuietly(directory);
        }
    }

    public ImageTextResult extract(Path image, String languages) throws IOException {
        if (!enabled) {
            throw new OcrFailure(OcrFailure.NO_ENGINE, "OCR 已被关闭（payroll.kb.ocr.enabled=false）", List.of());
        }
        byte[] bytes = Files.readAllBytes(image);
        String sha256 = sha256(bytes);
        String normalizedLanguages =
                languages == null || languages.isBlank() ? "chi_sim+eng" : languages.strip();
        List<ExtractionMeta.Attempt> attempts = new ArrayList<>();
        ImageTextResult best = null;
        String lastError = null;
        boolean anyEngineReady = false;

        for (String engineId : engineOrder()) {
            ImageTextExtractor extractor = extractors.get(engineId);
            if (extractor == null) {
                attempts.add(attempt(engineId, null, null, 0, 0, false, null, "未注册"));
                continue;
            }
            if (!extractor.available()) {
                attempts.add(attempt(engineId, extractor.model(), null, 0, 0, false, null, "引擎未就绪"));
                continue;
            }
            anyEngineReady = true;
            if (breakerOpen(engineId)) {
                attempts.add(attempt(engineId, extractor.model(), null, 0, 0, false, null, "熔断中"));
                continue;
            }
            String cacheKey = sha256 + "|" + engineId + "|" + normalizedLanguages;
            ImageTextExtractor.Result raw;
            long latency;
            boolean cached;
            synchronized (cache) {
                raw = cache.get(cacheKey);
            }
            if (raw != null) {
                latency = 0;
                cached = true;
            } else {
                long started = System.currentTimeMillis();
                try {
                    raw = runWithTimeout(extractor, image, normalizedLanguages);
                } catch (OcrFailure e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("OCR 被中断", e);
                } catch (Exception e) {
                    latency = System.currentTimeMillis() - started;
                    breakerFailure(engineId);
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    attempts.add(
                            attempt(
                                    engineId,
                                    extractor.model(),
                                    null,
                                    latency,
                                    0,
                                    false,
                                    null,
                                    lastError));
                    log.warn("[OCR] {} 识别失败：{}", engineId, lastError);
                    continue;
                }
                latency = System.currentTimeMillis() - started;
                cached = false;
                putCache(cacheKey, raw);
                breakerSuccess(engineId);
            }
            OcrQualityGate.Verdict verdict = gate.evaluate(raw.text(), raw.confidence());
            attempts.add(
                    attempt(
                            engineId,
                            extractor.model(),
                            raw.confidence(),
                            latency,
                            raw.text() == null ? 0 : raw.text().strip().length(),
                            verdict.passed(),
                            verdict.detail(),
                            null));
            ImageTextResult candidate =
                    new ImageTextResult(
                            raw.text() == null ? "" : raw.text(),
                            engineId,
                            extractor.model(),
                            extractor.version(),
                            normalizedLanguages,
                            extractor.psm(),
                            raw.confidence(),
                            latency,
                            raw.words(),
                            raw.lowConfidenceWords(),
                            sha256,
                            verdict.passed(),
                            verdict.detail(),
                            attempts,
                            cached);
            if (verdict.passed()) {
                return candidate;
            }
            if (best == null || score(candidate) > score(best)) {
                best = candidate;
            }
        }

        if (best != null) {
            throw new OcrFailure(
                    OcrFailure.NO_TEXT,
                    "识别结果未达到质量门：" + best.engine() + "（" + best.gateDetail() + "）",
                    attempts);
        }
        if (!anyEngineReady) {
            throw new OcrFailure(
                    OcrFailure.NO_ENGINE,
                    "未找到可用的 OCR 引擎；请安装 Tesseract（含 chi_sim 语言包），"
                            + "或以 -Prapidocr 构建并启用 RapidOCR",
                    attempts);
        }
        throw new OcrFailure(
                OcrFailure.ENGINE_ERROR,
                lastError == null ? "所有 OCR 档位均识别失败" : "OCR 识别失败：" + lastError,
                attempts);
    }

    private ImageTextExtractor.Result runWithTimeout(
            ImageTextExtractor extractor, Path image, String languages) throws Exception {
        int timeout = Math.max(5, extractor.timeoutSeconds());
        Future<ImageTextExtractor.Result> future =
                timeoutExecutor.submit(() -> extractor.extract(image, languages));
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("识别超时（" + timeout + "s）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    private boolean breakerOpen(String engineId) {
        Breaker breaker = breakers.get(engineId);
        if (breaker == null) {
            return false;
        }
        synchronized (breaker) {
            if (breaker.failures < Math.max(1, properties.getBreakerFailures())) {
                return false;
            }
            if (System.currentTimeMillis() < breaker.openedUntil) {
                return true;
            }
            breaker.failures = 0;
            breaker.openedUntil = 0;
            return false;
        }
    }

    private void breakerFailure(String engineId) {
        Breaker breaker = breakers.computeIfAbsent(engineId, key -> new Breaker());
        synchronized (breaker) {
            breaker.failures++;
            if (breaker.failures >= Math.max(1, properties.getBreakerFailures())) {
                breaker.openedUntil =
                        System.currentTimeMillis()
                                + Math.max(5, properties.getBreakerCooldownSeconds()) * 1000L;
                log.warn(
                        "[OCR] {} 连续失败 {} 次，熔断 {}s",
                        engineId,
                        breaker.failures,
                        properties.getBreakerCooldownSeconds());
            }
        }
    }

    private void breakerSuccess(String engineId) {
        Breaker breaker = breakers.get(engineId);
        if (breaker == null) {
            return;
        }
        synchronized (breaker) {
            breaker.failures = 0;
            breaker.openedUntil = 0;
        }
    }

    private void putCache(String key, ImageTextExtractor.Result result) {
        synchronized (cache) {
            cache.put(key, result);
        }
    }

    private Map<String, ImageTextExtractor.Result> newCache(int maxEntries) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ImageTextExtractor.Result> eldest) {
                return size() > maxEntries;
            }
        };
    }

    private static ExtractionMeta.Attempt attempt(
            String engine,
            String model,
            Double confidence,
            long latencyMs,
            int chars,
            boolean passed,
            String detail,
            String error) {
        return new ExtractionMeta.Attempt(
                engine, model, confidence, latencyMs, chars, passed, detail, error);
    }

    private static double score(ImageTextResult result) {
        double ratio = result.gatePassed() ? 1 : 0;
        return ratio * 10_000 + result.text().strip().length() + result.confidence();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
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

    private static void deleteQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 临时文件清理失败不影响识别结果。
                                }
                            });
        } catch (IOException ignored) {
            // 目录已不存在或不可读。
        }
    }

    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    private static final class Breaker {
        private int failures;
        private long openedUntil;
    }
}
