package io.agentscope.examples.payroll.kb.parse;

import java.util.List;

/** 解析入口支持的扩展名；Web 上传校验与解析服务共用同一份清单。 */
public final class KbParseFormats {

    public static final List<String> SUPPORTED =
            List.of("pdf", "docx", "xlsx", "xls", "md", "markdown");

    /** 对话附件支持的格式：PDF / Word / Excel / 图片（图片走引擎链 OCR）。 */
    public static final List<String> CHAT_SUPPORTED =
            List.of(
                    "pdf", "docx", "xlsx", "xls", "png", "jpg", "jpeg", "gif", "bmp", "tif",
                    "tiff");

    private KbParseFormats() {
    }
}
