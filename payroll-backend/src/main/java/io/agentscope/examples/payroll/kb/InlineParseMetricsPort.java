package io.agentscope.examples.payroll.kb;

import io.agentscope.examples.payroll.kb.parse.ChatExtraction;
import io.agentscope.examples.payroll.kb.parse.KbParseListener;
import io.agentscope.examples.payroll.kb.parse.KbParseRequest;
import io.agentscope.examples.payroll.kb.parse.KbParsedDocument;
import io.agentscope.examples.payroll.kb.parse.KbParserPort;
import io.agentscope.examples.payroll.kb.parse.ParseMetrics;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** inline 模式的解析端口装饰器：真实解析引擎不对外暴露，engine 统一记为 inline。 */
public class InlineParseMetricsPort implements KbParserPort {

    private final KbParserPort delegate;

    public InlineParseMetricsPort(KbParserPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public KbParsedDocument parse(
            KbParseRequest request, KbParseListener listener, BooleanSupplier canceled) {
        long startedNanos = ParseMetrics.start();
        try {
            KbParsedDocument document = delegate.parse(request, listener, canceled);
            ParseMetrics.duration("success", "inline", startedNanos);
            return document;
        } catch (RuntimeException e) {
            ParseMetrics.failure(ParseMetrics.reason(e));
            ParseMetrics.duration("failed", "inline", startedNanos);
            throw e;
        }
    }

    @Override
    public ChatExtraction parseChat(Path file, String ext) {
        long startedNanos = ParseMetrics.start();
        try {
            ChatExtraction extraction = delegate.parseChat(file, ext);
            String engine =
                    extraction.meta() == null || extraction.meta().engine() == null
                            ? "inline"
                            : extraction.meta().engine();
            ParseMetrics.duration("success", engine, startedNanos);
            return extraction;
        } catch (RuntimeException e) {
            ParseMetrics.failure(ParseMetrics.reason(e));
            ParseMetrics.duration("failed", "inline", startedNanos);
            throw e;
        }
    }
}
