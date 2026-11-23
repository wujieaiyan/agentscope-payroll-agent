package io.agentscope.examples.payroll.kb.ocr;

import io.agentscope.examples.payroll.kb.KbParserService;

/** 入库取消信号：不是识别失败，解析层按控制流原样向上传递。 */
public class OcrCanceled extends RuntimeException implements KbParserService.AbortSignal {

    public OcrCanceled() {
        super("OCR 已取消");
    }
}
