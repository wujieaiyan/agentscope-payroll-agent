package io.agentscope.payroll.parser.ocr;

import io.agentscope.payroll.parser.KbParserService;

/** 入库取消信号：不是识别失败，解析层按控制流原样向上传递。 */
public class OcrCanceled extends RuntimeException implements io.agentscope.examples.payroll.kb.parse.KbAbortSignal {

    public OcrCanceled() {
        super("OCR 已取消");
    }
}
