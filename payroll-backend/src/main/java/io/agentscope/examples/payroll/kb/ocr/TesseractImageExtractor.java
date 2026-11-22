package io.agentscope.examples.payroll.kb.ocr;

import io.agentscope.examples.payroll.kb.KbOcrService;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** 降级档：沿用 Tesseract CLI，保留既有 tessdata 与超时配置。 */
@Component
public class TesseractImageExtractor implements ImageTextExtractor {

    private final KbOcrService ocrService;

    public TesseractImageExtractor(KbOcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Override
    public String id() {
        return "tesseract";
    }

    @Override
    public String model() {
        return "tesseract-cli";
    }

    @Override
    public String version() {
        return "";
    }

    @Override
    public boolean available() {
        return ocrService.enabled() && ocrService.available();
    }

    @Override
    public Integer psm() {
        return 3;
    }

    @Override
    public int timeoutSeconds() {
        return ocrService.timeoutSeconds();
    }

    @Override
    public Result extract(Path image, String languages) throws Exception {
        KbOcrService.OcrText text = ocrService.recognizeFile(image, languages);
        return new Result(
                text.text(), text.confidence(), 0, text.words(), text.lowConfidenceWords());
    }
}
