package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.kb.parse.KbParseClient;
import io.agentscope.examples.payroll.kb.parse.KbParseSettings;
import io.agentscope.examples.payroll.kb.parse.KbParserPort;
import io.agentscope.payroll.parser.KbParserService;
import io.agentscope.payroll.parser.ocr.ImageTextExtractor;
import io.agentscope.payroll.parser.ocr.KbOcrService;
import io.agentscope.payroll.parser.ocr.OcrEngineProperties;
import io.agentscope.payroll.parser.ocr.OcrExtractionService;
import io.agentscope.payroll.parser.ocr.RapidOcrExtractor;
import io.agentscope.payroll.parser.ocr.TesseractImageExtractor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析端口装配：默认 inline 复用 parser 模块实现；remote 走独立解析服务并做启动校验。
 *
 * <p>remote 模式下不创建任何 OCR/解析 Bean，Web 进程不再加载 PDFBox/POI/OCR 运行时。
 */
@Configuration
@EnableConfigurationProperties
public class KbParserConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "payroll.kb.parse")
    public KbParseSettings kbParseSettings() {
        return new KbParseSettings();
    }

    @Bean
    @ConditionalOnProperty(
            name = "payroll.kb.parse.mode",
            havingValue = "inline",
            matchIfMissing = true)
    public KbParserPort inlineKbParserPort(
            @Value("${payroll.kb.ocr.enabled:true}") boolean ocrEnabled,
            @Value("${payroll.kb.ocr.command:tesseract}") String command,
            @Value("${payroll.kb.ocr.tessdata-dir:}") String tessdataDir,
            @Value("${payroll.kb.ocr.languages:chi_sim+eng}") String languages,
            @Value("${payroll.kb.ocr.dpi:200}") int dpi,
            @Value("${payroll.kb.ocr.page-timeout-seconds:120}") int pageTimeoutSeconds,
            @Value("${payroll.kb.ocr.max-pages:0}") int ocrMaxPages,
            @Value("${payroll.kb.ocr.concurrency:2}") int concurrency,
            @Value("${payroll.kb.ocr.timeout-seconds:600}") int docBudgetSeconds,
            @Value("${payroll.kb.ocr.engine:chain}") String engine,
            @Value("${payroll.kb.ocr.chain-order:rapidocr,tesseract}") String chainOrder,
            @Value("${payroll.kb.ocr.cache-size:32}") int cacheSize,
            @Value("${payroll.kb.ocr.breaker-failures:3}") int breakerFailures,
            @Value("${payroll.kb.ocr.breaker-cooldown-seconds:60}") int breakerCooldownSeconds,
            @Value("${payroll.kb.ocr.rapidocr.enabled:true}") boolean rapidOcrEnabled,
            @Value("${payroll.kb.ocr.rapidocr.model-dir:}") String rapidOcrModelDir,
            @Value("${payroll.kb.ocr.rapidocr.timeout-seconds:60}") int rapidOcrTimeout,
            @Value("${payroll.kb.parse.max-pages:200}") int parseMaxPages) {
        KbOcrService ocrService =
                new KbOcrService(
                        ocrEnabled,
                        command,
                        tessdataDir,
                        languages,
                        dpi,
                        pageTimeoutSeconds,
                        ocrMaxPages,
                        concurrency,
                        docBudgetSeconds);
        OcrEngineProperties properties = new OcrEngineProperties();
        properties.setEngine(engine);
        properties.setChainOrder(chainOrder);
        properties.setCacheSize(cacheSize);
        properties.setBreakerFailures(breakerFailures);
        properties.setBreakerCooldownSeconds(breakerCooldownSeconds);
        properties.getRapidocr().setEnabled(rapidOcrEnabled);
        properties.getRapidocr().setModelDir(rapidOcrModelDir);
        properties.getRapidocr().setTimeoutSeconds(rapidOcrTimeout);
        List<ImageTextExtractor> extractors =
                List.of(
                        new RapidOcrExtractor(
                                rapidOcrEnabled, rapidOcrModelDir, rapidOcrTimeout),
                        new TesseractImageExtractor(ocrService));
        OcrExtractionService extractionService =
                new OcrExtractionService(extractors, properties, ocrEnabled);
        return new InlineParseMetricsPort(
                new KbParserService(ocrService, extractionService, parseMaxPages));
    }

    @Bean
    @ConditionalOnProperty(name = "payroll.kb.parse.mode", havingValue = "remote")
    public KbParserPort remoteKbParserPort(KbParseSettings settings) {
        return new KbParseClient(settings);
    }
}
