package io.agentscope.examples.payroll.kb.parse;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** 解析端口：进程内实现直接调用解析器，远程实现转发到独立解析服务。 */
public interface KbParserPort {

    KbParsedDocument parse(KbParseRequest request, KbParseListener listener, BooleanSupplier canceled);

    ChatExtraction parseChat(Path file, String ext);

    default boolean remote() {
        return false;
    }
}
