package io.agentscope.examples.payroll.kb.parse;

/** 解析进度回调，仅扫描页 OCR 阶段触发；抛出的异常会原样向上传递。 */
@FunctionalInterface
public interface KbParseListener {

    KbParseListener NONE = (pageNo, totalPages, ocrPages) -> {
    };

    void onPageProgress(int pageNo, int totalPages, int ocrPages);
}
