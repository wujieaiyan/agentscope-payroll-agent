package io.agentscope.payroll.parser.ocr;

import java.nio.file.Path;

/**
 * 图片文字提取端口。适配器隔离第三方 OCR API，质量门与档位选择由
 * {@link OcrExtractionService} 负责，禁止在业务代码里按引擎名分支。
 */
public interface ImageTextExtractor {

    /** 稳定标识，用于配置里的档位顺序，如 tesseract / rapidocr。 */
    String id();

    String model();

    String version();

    /** 只探测依赖是否就绪，不应触发重量级模型加载。 */
    boolean available();

    /** 阻塞识别；超时、熔断与缓存由调用方统一处理。 */
    Result extract(Path image, String languages) throws Exception;

    /** Tesseract 的分页模式；其他引擎返回 null。 */
    default Integer psm() {
        return null;
    }

    /** 单次识别超时；调用方用独立线程执行并在超时后取消。 */
    default int timeoutSeconds() {
        return 60;
    }

    record Result(String text, double confidence, long latencyMs, int words, int lowConfidenceWords) {
    }
}
