package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.devteam.DevCodeIndex;
import io.agentscope.examples.payroll.devteam.DevCodeSymbol;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码符号检索工具（P2-2）：只读查询工作区 tree-sitter 索引，按符号名或路径定位
 * Java/JS/TS/Vue script/Bash 中的类型、方法与函数。
 *
 * <p>索引在首次查询时构建，作用于智能体创建时的工作区快照；索引失败时返回错误文本
 * 而不是抛异常，模型仍可退回 grep/glob。
 */
public final class DevCodeIndexTools {

    public static final String SEARCH_CODE_INDEX = "search_code_index";

    private static final Logger log = LoggerFactory.getLogger(DevCodeIndexTools.class);

    private final Path root;
    private volatile DevCodeIndex index;

    public DevCodeIndexTools(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("代码索引工作区不能为空");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Tool(
            name = SEARCH_CODE_INDEX,
            description =
                    "按符号名或路径检索代码索引（Java/JavaScript/TypeScript/Vue script/Bash），"
                            + "返回「文件:行号 [类型] 名称 — 签名」列表。先定位再读文件，"
                            + "适合不确定符号所在文件时使用。",
            readOnly = true)
    public String search(
            @ToolParam(name = "query", description = "符号名或路径片段，不区分大小写") String query,
            @ToolParam(
                            name = "kind",
                            description = "可选，过滤符号类型：class/interface/enum/record/type/method/"
                                    + "constructor/function/field/property/package",
                            required = false)
                    String kind,
            @ToolParam(name = "limit", description = "可选，返回条数，默认 20，最大 50", required = false)
                    Integer limit,
            RuntimeContext context) {
        if (query == null || query.isBlank()) {
            return "请提供要检索的符号名或路径片段";
        }
        try {
            List<DevCodeSymbol> found =
                    index().search(query, kind, limit == null ? DevCodeIndex.DEFAULT_LIMIT : limit);
            if (found.isEmpty()) {
                return "代码索引无匹配：" + query.trim();
            }
            return DevCodeIndex.format(found);
        } catch (RuntimeException e) {
            log.warn("[代码索引] 检索失败：query={}，原因={}", query, e.getMessage());
            return "代码索引不可用：" + e.getMessage() + "；请改用 grep/glob 检索";
        }
    }

    private DevCodeIndex index() {
        DevCodeIndex current = index;
        if (current == null) {
            synchronized (this) {
                current = index;
                if (current == null) {
                    current = DevCodeIndex.scan(root);
                    index = current;
                }
            }
        }
        return current;
    }
}
