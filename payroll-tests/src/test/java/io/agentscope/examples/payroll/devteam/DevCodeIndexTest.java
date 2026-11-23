package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.agent.DevCodeIndexTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevCodeIndexTest {

    @TempDir Path temp;

    @Test
    @DisplayName("Java：抽取包、类、构造器、方法、字段与嵌套类型，并记录所在类型")
    void indexesJavaSymbols() throws IOException {
        Path file = write("src/main/java/demo/Calculator.java", javaSource());

        DevCodeIndex index = DevCodeIndex.scan(temp);
        List<DevCodeSymbol> symbols = index.symbols();

        assertTrue(has(symbols, "package", "demo", ""));
        assertTrue(has(symbols, "class", "Calculator", ""));
        assertTrue(has(symbols, "field", "base", "Calculator"));
        assertTrue(has(symbols, "constructor", "Calculator", "Calculator"));
        assertTrue(has(symbols, "method", "add", "Calculator"));
        assertTrue(has(symbols, "record", "Sum", "Calculator"));
        assertTrue(has(symbols, "enum", "Mode", "Calculator"));
        assertTrue(has(symbols, "interface", "Operation", "Calculator"));

        DevCodeSymbol add = find(symbols, "method", "add");
        assertEquals("src/main/java/demo/Calculator.java", add.path());
        assertEquals(9, add.startLine());
        assertEquals("public int add(int a, int b)", add.signature());
        assertEquals("java", add.language());
        assertEquals(1, index.fileCount());
        assertEquals(file.toString(), temp.resolve(add.path()).toString());
    }

    @Test
    @DisplayName("JavaScript/TypeScript：函数、类、方法与箭头函数，接口/类型别名")
    void indexesJavaScriptAndTypeScript() throws IOException {
        write("web/app.js", jsSource());
        write("web/types.ts", tsSource());

        DevCodeIndex index = DevCodeIndex.scan(temp);
        List<DevCodeSymbol> symbols = index.symbols();

        assertTrue(has(symbols, "function", "loadUser", ""));
        assertTrue(has(symbols, "class", "Cart", ""));
        assertTrue(has(symbols, "method", "add", "Cart"));
        assertTrue(has(symbols, "function", "total", ""));
        assertTrue(has(symbols, "interface", "Order", ""));
        assertTrue(has(symbols, "type", "Money", ""));
        assertTrue(has(symbols, "enum", "Status", ""));
        assertTrue(has(symbols, "function", "pay", ""));
        assertEquals(2, index.fileCount());
    }

    @Test
    @DisplayName("Vue：只索引 script 块，行号补上模板偏移")
    void indexesVueScriptWithLineOffset() throws IOException {
        write("frontend/src/views/Counter.vue", vueSource());

        DevCodeIndex index = DevCodeIndex.scan(temp);

        DevCodeSymbol increment = find(index.symbols(), "function", "increment");
        assertEquals("frontend/src/views/Counter.vue", increment.path());
        assertEquals(5, increment.startLine());
        assertFalse(has(index.symbols(), "function", "count", ""));
    }

    @Test
    @DisplayName("Bash：函数定义可检索")
    void indexesBashFunctions() throws IOException {
        write("scripts/run.sh", "#!/bin/bash\nrun_job() {\n  echo hi\n}\n");

        DevCodeIndex index = DevCodeIndex.scan(temp);

        DevCodeSymbol run = find(index.symbols(), "function", "run_job");
        assertEquals(2, run.startLine());
        assertEquals("bash", run.language());
    }

    @Test
    @DisplayName("跳过构建/依赖目录与不支持的文件，坏语法不影响其他文件")
    void skipsNoiseAndToleratesBrokenSource() throws IOException {
        write("node_modules/pkg/index.js", "function ignored() {}\n");
        write("target/classes/Skip.java", "class Skip {}\n");
        write("data/local.java", "class Local {}\n");
        write("README.md", "# doc\n");
        write("src/Broken.java", "class Broken {{{ ;;;\n");
        write("src/Fine.java", "class Fine {\n  void ok() {}\n}\n");

        DevCodeIndex index = DevCodeIndex.scan(temp);

        assertEquals(2, index.fileCount());
        assertTrue(index.search("skip", null, 10).isEmpty());
        assertTrue(index.search("ignored", null, 10).isEmpty());
        assertFalse(index.search("ok", null, 10).isEmpty());
    }

    @Test
    @DisplayName("检索：完全匹配优先，支持类型过滤与条数上限")
    void searchRanksAndFilters() throws IOException {
        write(
                "src/Run.java",
                "class Run {\n"
                        + "  void run() {}\n"
                        + "  void runAll() {}\n"
                        + "  void prerun() {}\n"
                        + "}\n");

        DevCodeIndex index = DevCodeIndex.scan(temp);

        List<DevCodeSymbol> all = index.search("RUN", null, 10);
        assertEquals("Run", all.get(0).name());
        assertEquals("run", all.get(1).name());

        List<DevCodeSymbol> limited = index.search("run", "method", 1);
        assertEquals(1, limited.size());
        assertEquals("run", limited.get(0).name());

        List<DevCodeSymbol> methodsOnly = index.search("RUN", "method", 10);
        assertEquals(
                List.of("run", "runAll", "prerun"),
                methodsOnly.stream().map(DevCodeSymbol::name).toList());

        assertTrue(index.search("Run", "class", 10).stream()
                .allMatch(s -> "class".equals(s.kind())));
    }

    @Test
    @DisplayName("检索：名字不命中时按路径片段兜底")
    void searchFallsBackToPath() throws IOException {
        write("src/CartService.java", "class CartService {\n  void refresh() {}\n}\n");

        DevCodeIndex index = DevCodeIndex.scan(temp);

        List<DevCodeSymbol> byPath = index.search("cartservice", null, 10);
        assertFalse(byPath.isEmpty());
        assertTrue(byPath.stream().allMatch(s -> s.path().contains("CartService.java")));
    }

    @Test
    @DisplayName("工具：格式化为 文件:行号 [类型] 名称，空查询与无匹配给出提示")
    void toolFormatsResults() throws IOException {
        write("src/main/java/demo/Calculator.java", javaSource());
        DevCodeIndexTools tools = new DevCodeIndexTools(temp);

        String found = tools.search("add", "method", 5, null);
        assertEquals(
                "src/main/java/demo/Calculator.java:9 [method] add — public int add(int a, int b)"
                        + "（在 Calculator 内）\n",
                found);

        assertTrue(tools.search("  ", null, null, null).contains("请提供"));
        assertTrue(tools.search("missingSymbol", null, null, null).contains("代码索引无匹配"));
    }

    private Path write(String relative, String content) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static boolean has(List<DevCodeSymbol> symbols, String kind, String name, String parent) {
        return symbols.stream()
                .anyMatch(
                        s ->
                                s.kind().equals(kind)
                                        && s.name().equals(name)
                                        && s.parent().equals(parent));
    }

    private static DevCodeSymbol find(List<DevCodeSymbol> symbols, String kind, String name) {
        return symbols.stream()
                .filter(s -> s.kind().equals(kind) && s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少符号：" + kind + " " + name));
    }

    private static String javaSource() {
        return "package demo;\n"
                + "\n"
                + "public class Calculator {\n"
                + "    private int base;\n"
                + "\n"
                + "    public Calculator() {\n"
                + "    }\n"
                + "\n"
                + "    public int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n"
                + "\n"
                + "    record Sum(int value) {\n"
                + "    }\n"
                + "\n"
                + "    enum Mode { SIMPLE }\n"
                + "\n"
                + "    interface Operation {\n"
                + "    }\n"
                + "}\n";
    }

    private static String jsSource() {
        return "export function loadUser(id) { return id; }\n"
                + "class Cart {\n"
                + "  add(item) { return item; }\n"
                + "}\n"
                + "const total = (a) => a;\n";
    }

    private static String tsSource() {
        return "export interface Order { id: string }\n"
                + "type Money = number;\n"
                + "export enum Status { NEW }\n"
                + "export function pay(x: number): void {}\n";
    }

    private static String vueSource() {
        return "<template><div>{{ count }}</div></template>\n"
                + "<script setup>\n"
                + "import { ref } from 'vue';\n"
                + "const count = ref(0);\n"
                + "function increment() {\n"
                + "  count.value++;\n"
                + "}\n"
                + "</script>\n";
    }
}
