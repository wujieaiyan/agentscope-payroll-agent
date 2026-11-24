package io.agentscope.payroll.parser;

import io.agentscope.examples.payroll.kb.parse.KbParseSettings;
import io.agentscope.examples.payroll.kb.parse.ParseMetrics;
import io.agentscope.payroll.parser.ocr.ImageTextExtractor;
import io.agentscope.payroll.parser.ocr.KbOcrService;
import io.agentscope.payroll.parser.ocr.OcrEngineProperties;
import io.agentscope.payroll.parser.ocr.OcrExtractionService;
import io.agentscope.payroll.parser.ocr.RapidOcrExtractor;
import io.agentscope.payroll.parser.ocr.TesseractImageExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 解析进程的显式装配：解析器类不带 Spring 注解，便于 Web inline 复用同一套实现。 */
@Configuration
@EnableConfigurationProperties
public class ParserConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ParserConfiguration.class);

    /** 启动后延迟清理，避开解析高峰；之后按 {@code cleanup-interval-hours} 每天执行。 */
    private static final long CLEANUP_STARTUP_DELAY_SECONDS = 30L;

    @Bean
    @ConfigurationProperties(prefix = "payroll.kb.parse")
    public KbParseSettings kbParseSettings() {
        return new KbParseSettings();
    }

    @Bean
    @ConfigurationProperties(prefix = "payroll.kb.ocr")
    public OcrEngineProperties ocrEngineProperties() {
        return new OcrEngineProperties();
    }

    @Bean
    public KbOcrService kbOcrService(
            @Value("${payroll.kb.ocr.enabled:true}") boolean enabled,
            @Value("${payroll.kb.ocr.command:tesseract}") String command,
            @Value("${payroll.kb.ocr.tessdata-dir:}") String tessdataDir,
            @Value("${payroll.kb.ocr.languages:chi_sim+eng}") String languages,
            @Value("${payroll.kb.ocr.dpi:200}") int dpi,
            @Value("${payroll.kb.ocr.page-timeout-seconds:120}") int pageTimeoutSeconds,
            @Value("${payroll.kb.ocr.max-pages:0}") int maxPages,
            @Value("${payroll.kb.ocr.concurrency:2}") int concurrency,
            @Value("${payroll.kb.ocr.timeout-seconds:600}") int docBudgetSeconds) {
        return new KbOcrService(
                enabled,
                command,
                tessdataDir,
                languages,
                dpi,
                pageTimeoutSeconds,
                maxPages,
                concurrency,
                docBudgetSeconds);
    }

    @Bean
    public RapidOcrExtractor rapidOcrExtractor(
            @Value("${payroll.kb.ocr.rapidocr.enabled:true}") boolean enabled,
            @Value("${payroll.kb.ocr.rapidocr.model-dir:}") String modelDir,
            @Value("${payroll.kb.ocr.rapidocr.timeout-seconds:60}") int timeoutSeconds) {
        return new RapidOcrExtractor(enabled, modelDir, timeoutSeconds);
    }

    @Bean
    public TesseractImageExtractor tesseractImageExtractor(KbOcrService ocrService) {
        return new TesseractImageExtractor(ocrService);
    }

    @Bean
    public OcrExtractionService ocrExtractionService(
            List<ImageTextExtractor> extractors,
            OcrEngineProperties properties,
            @Value("${payroll.kb.ocr.enabled:true}") boolean enabled) {
        return new OcrExtractionService(extractors, properties, enabled);
    }

    @Bean
    public KbParserService kbParserService(
            KbOcrService ocrService,
            OcrExtractionService extractionService,
            @Value("${payroll.kb.parse.max-pages:200}") int parseMaxPages) {
        return new KbParserService(ocrService, extractionService, parseMaxPages);
    }

    @Bean(name = "artifactCleanupScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService artifactCleanupScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "artifact-cleanup");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 解析产物保留：每文档留最新 2 个版本，其余超过 TTL 的目录由解析进程每日删除。 */
    @Bean
    public ApplicationRunner artifactCleanupRunner(
            KbParseSettings settings,
            MeterRegistry registry,
            @Qualifier("artifactCleanupScheduler") ScheduledExecutorService scheduler,
            @Value("${payroll.kb.parse.cleanup-enabled:true}") boolean enabled,
            @Value("${payroll.kb.parse.artifact-ttl-days:7}") int ttlDays,
            @Value("${payroll.kb.parse.cleanup-interval-hours:24}") int intervalHours) {
        return args -> {
            if (!enabled) {
                log.info("[解析产物] 清理已关闭（payroll.kb.parse.cleanup-enabled=false）");
                return;
            }
            ArtifactCleaner cleaner =
                    new ArtifactCleaner(Path.of(settings.artifactDir()), ttlDays, registry);
            long periodSeconds = Math.max(1, intervalHours) * 3600L;
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            cleaner.cleanup();
                        } catch (RuntimeException e) {
                            log.warn("[解析产物] 清理任务异常：{}", e.getMessage());
                        }
                    },
                    CLEANUP_STARTUP_DELAY_SECONDS,
                    periodSeconds,
                    TimeUnit.SECONDS);
            log.info(
                    "[解析产物] 清理计划：TTL={}天，保留最新 {} 个版本，每 {} 小时执行",
                    ttlDays,
                    ArtifactCleaner.KEEP_VERSIONS,
                    Math.max(1, intervalHours));
        };
    }

    /** 固定 2 个工作线程 + 直接交接，排队即拒绝（503），避免解析请求无限堆积。 */
    @Bean(name = "kbParseWorkers", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor kbParseWorkers(KbParseSettings settings) {
        AtomicInteger index = new AtomicInteger();
        ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        Math.max(1, settings.workerThreads()),
                        Math.max(1, settings.workerThreads()),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        runnable -> {
                            Thread thread =
                                    new Thread(runnable, "kb-parser-" + index.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        ParseMetrics.bindQueue(workers);
        return workers;
    }
}
