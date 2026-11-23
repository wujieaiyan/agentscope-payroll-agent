package io.agentscope.examples.payroll.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 classpath 的 {@code prompts/} 加载系统提示词 Markdown。
 *
 * <p>提示词与代码同版本发布；文件缺失时直接失败，避免静默退化成没有安全规则的提示词。
 * 加载结果按文件名缓存，运行期不再读盘。
 */
public final class PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);
    private static final String ROOT = "prompts/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLibrary() {
    }

    public static String get(String name) {
        return CACHE.computeIfAbsent(name, PromptLibrary::read);
    }

    private static String read(String name) {
        String path = ROOT + name + ".md";
        try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("提示词文件缺失：" + path);
            }
            // 统一为 LF，避免 Windows/CI 检出换行差异改变提示词内容。
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("[Prompt] 已加载：{}（{} 字）", path, text.length());
            return text.replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词失败：" + path, e);
        }
    }
}
