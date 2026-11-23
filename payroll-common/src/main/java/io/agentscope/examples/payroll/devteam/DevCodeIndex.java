package io.agentscope.examples.payroll.devteam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSLanguage;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterBash;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTypescript;

/**
 * 只读代码符号索引（P2-2）：按扩展名选择 tree-sitter 语法，抽取类型、方法与函数，
 * 供研发智能体在未读文件时按符号名或路径定位代码。
 *
 * <p>索引只覆盖 Java、JavaScript、TypeScript、Bash 与 Vue 单文件组件的
 * {@code <script>} 块；跳过构建产物与依赖目录；单文件上限 256KB，单次扫描最多
 * 5000 个文件、50000 个符号。解析失败的文件静默跳过，不影响其余结果。
 */
public final class DevCodeIndex {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    static final int MAX_FILES = 5_000;
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_SYMBOLS = 50_000;
    private static final int MAX_SIGNATURE_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(DevCodeIndex.class);
    private static final Set<String> SKIP_DIRS =
            Set.of(
                    ".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode",
                    "coverage", "data", ".mvn");

    private static final Map<String, String> KIND_BY_TYPE = new LinkedHashMap<>();
    private static final Set<String> TYPE_KINDS =
            Set.of("package", "class", "interface", "enum", "record", "annotation", "type");

    static {
        KIND_BY_TYPE.put("package_declaration", "package");
        KIND_BY_TYPE.put("class_declaration", "class");
        KIND_BY_TYPE.put("interface_declaration", "interface");
        KIND_BY_TYPE.put("enum_declaration", "enum");
        KIND_BY_TYPE.put("record_declaration", "record");
        KIND_BY_TYPE.put("annotation_type_declaration", "annotation");
        KIND_BY_TYPE.put("type_alias_declaration", "type");
        KIND_BY_TYPE.put("method_declaration", "method");
        KIND_BY_TYPE.put("constructor_declaration", "constructor");
        KIND_BY_TYPE.put("method_definition", "method");
        KIND_BY_TYPE.put("function_declaration", "function");
        KIND_BY_TYPE.put("function_definition", "function");
        KIND_BY_TYPE.put("field_declaration", "field");
        KIND_BY_TYPE.put("property_signature", "property");
    }

    private static final TSLanguage JAVA = new TreeSitterJava();
    private static final TSLanguage JAVASCRIPT = new TreeSitterJavascript();
    private static final TSLanguage TYPESCRIPT = new TreeSitterTypescript();
    private static final TSLanguage BASH = new TreeSitterBash();

    private final Path root;
    private final List<DevCodeSymbol> symbols;
    private final int fileCount;

    private DevCodeIndex(Path root, List<DevCodeSymbol> symbols, int fileCount) {
        this.root = root;
        this.symbols = List.copyOf(symbols);
        this.fileCount = fileCount;
    }

    public static DevCodeIndex scan(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("代码索引根目录不能为空");
        }
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return new DevCodeIndex(base, List.of(), 0);
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(
                    base,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(base)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = dir.getFileName().toString();
                            if (SKIP_DIRS.contains(name) || files.size() >= MAX_FILES) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (files.size() >= MAX_FILES) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (attrs.isRegularFile()
                                    && attrs.size() <= MAX_FILE_BYTES
                                    && languageOf(file) != null) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("[代码索引] 遍历工作区失败，返回已收集结果：root={}，原因={}", base, e.getMessage());
        }
        files.sort(Comparator.comparing(Path::toString));
        List<DevCodeSymbol> symbols = new ArrayList<>();
        int indexed = 0;
        TSParser parser = new TSParser();
        for (Path file : files) {
            if (symbols.size() >= MAX_SYMBOLS) {
                break;
            }
            TSLanguage language = languageOf(file);
            if (language == null || !parser.setLanguage(language)) {
                continue;
            }
            if (indexFile(parser, base, file, language, symbols)) {
                indexed++;
            }
        }
        log.info(
                "[代码索引] 扫描完成：root={}，文件={}，符号={}，候选={}",
                base,
                indexed,
                symbols.size(),
                files.size());
        return new DevCodeIndex(base, symbols, indexed);
    }

    public Path root() {
        return root;
    }

    public List<DevCodeSymbol> symbols() {
        return symbols;
    }

    public int fileCount() {
        return fileCount;
    }

    /**
     * 按符号名与路径检索：完全匹配优先，其次前缀、包含，最后按路径命中；
     * 同一符号只在名字命中时参与排序。
     */
    public List<DevCodeSymbol> search(String query, String kind, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        String kindFilter = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        record Scored(DevCodeSymbol symbol, int score) {}
        List<Scored> matched = new ArrayList<>();
        for (DevCodeSymbol symbol : symbols) {
            if (!kindFilter.isEmpty()
                    && !symbol.kind().toLowerCase(Locale.ROOT).equals(kindFilter)) {
                continue;
            }
            String name = symbol.name().toLowerCase(Locale.ROOT);
            int score;
            if (name.equals(q)) {
                score = 0;
            } else if (name.startsWith(q)) {
                score = 1;
            } else if (name.contains(q)) {
                score = 2;
            } else if (symbol.path().toLowerCase(Locale.ROOT).contains(q)) {
                score = 3;
            } else {
                continue;
            }
            matched.add(new Scored(symbol, score));
            if (matched.size() >= capped * 20L) {
                break;
            }
        }
        return matched.stream()
                .sorted(
                        Comparator.comparingInt(Scored::score)
                                .thenComparing(s -> s.symbol().path())
                                .thenComparingInt(s -> s.symbol().startLine()))
                .limit(capped)
                .map(Scored::symbol)
                .toList();
    }

    /** 工具输出格式：每行一条，附带 kind、名称、签名与所在类型。 */
    public static String format(List<DevCodeSymbol> symbols) {
        StringBuilder out = new StringBuilder();
        for (DevCodeSymbol symbol : symbols) {
            out.append(symbol.path())
                    .append(':')
                    .append(symbol.startLine())
                    .append(" [")
                    .append(symbol.kind())
                    .append("] ")
                    .append(symbol.name());
            if (!symbol.signature().isEmpty()) {
                out.append(" — ").append(symbol.signature());
            }
            if (!symbol.parent().isEmpty()) {
                out.append("（在 ").append(symbol.parent()).append(" 内）");
            }
            out.append('\n');
        }
        return out.toString();
    }

    static TSLanguage languageOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return JAVA;
        }
        if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
            return JAVASCRIPT;
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            return TYPESCRIPT;
        }
        if (name.endsWith(".sh") || name.endsWith(".bash")) {
            return BASH;
        }
        if (name.endsWith(".vue")) {
            return JAVASCRIPT;
        }
        return null;
    }

    private static boolean indexFile(
            TSParser parser, Path base, Path file, TSLanguage language, List<DevCodeSymbol> out) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        String path = base.relativize(file).toString().replace('\\', '/');
        int lineOffset = 0;
        if (path.toLowerCase(Locale.ROOT).endsWith(".vue")) {
            int[] extracted = extractVueScript(source);
            if (extracted == null) {
                return false;
            }
            lineOffset = extracted[0];
            source = source.substring(extracted[1], extracted[2]);
        }
        TSTree tree;
        try {
            tree = parser.parseString(null, source);
        } catch (RuntimeException e) {
            return false;
        }
        if (tree == null) {
            return false;
        }
        FileContext context =
                new FileContext(
                        path,
                        languageName(language),
                        source.getBytes(StandardCharsets.UTF_8),
                        lineOffset,
                        out);
        visit(tree.getRootNode(), "", context);
        return true;
    }

    private static void visit(TSNode node, String parent, FileContext context) {
        if (context.out.size() >= MAX_SYMBOLS) {
            return;
        }
        String type = node.getType();
        String kind = KIND_BY_TYPE.get(type);
        if ("variable_declarator".equals(type)) {
            TSNode value = node.getChildByFieldName("value");
            if (value != null
                    && !value.isNull()
                    && ("arrow_function".equals(value.getType())
                            || "function_expression".equals(value.getType()))) {
                kind = "function";
            }
        }
        if (kind != null) {
            String name = nameOf(node, type, context);
            if (name != null && !name.isBlank()) {
                context.out.add(
                        new DevCodeSymbol(
                                context.path,
                                context.language,
                                kind,
                                name,
                                parent,
                                signature(node, context),
                                node.getStartPoint().getRow() + context.lineOffset + 1,
                                node.getEndPoint().getRow() + context.lineOffset + 1));
                if (TYPE_KINDS.contains(kind)) {
                    parent = name;
                }
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getChild(i);
            if (child.isNamed()) {
                visit(child, parent, context);
            }
        }
    }

    private static String nameOf(TSNode node, String type, FileContext context) {
        if ("package_declaration".equals(type)) {
            return node.getNamedChildCount() > 0 ? text(node.getNamedChild(0), context) : null;
        }
        if ("field_declaration".equals(type)) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode child = node.getNamedChild(i);
                if ("variable_declarator".equals(child.getType())) {
                    TSNode name = child.getChildByFieldName("name");
                    return name == null || name.isNull() ? null : text(name, context);
                }
            }
            return null;
        }
        TSNode name = node.getChildByFieldName("name");
        if (name == null || name.isNull()) {
            return null;
        }
        return text(name, context);
    }

    private static String signature(TSNode node, FileContext context) {
        byte[] bytes = context.bytes;
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), bytes.length);
        int limit = Math.min(end, start + 400);
        String raw = new String(bytes, start, Math.max(0, limit - start), StandardCharsets.UTF_8);
        int cut = raw.length();
        int newline = raw.indexOf('\n');
        if (newline >= 0) {
            cut = newline;
        }
        int brace = raw.indexOf('{');
        if (brace >= 0 && brace < cut) {
            cut = brace;
        }
        int semicolon = raw.indexOf(';');
        if (semicolon >= 0 && semicolon < cut) {
            cut = semicolon;
        }
        String signature = raw.substring(0, cut).replaceAll("\\s+", " ").trim();
        if (signature.endsWith(")")) {
            return signature;
        }
        return signature.length() > MAX_SIGNATURE_CHARS
                ? signature.substring(0, MAX_SIGNATURE_CHARS)
                : signature;
    }

    private static String text(TSNode node, FileContext context) {
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), context.bytes.length);
        if (start < 0 || end <= start) {
            return "";
        }
        return new String(context.bytes, start, end - start, StandardCharsets.UTF_8);
    }

    /** 返回 {行偏移, 脚本块起始下标, 结束下标}；无 script 块时返回 null。 */
    private static int[] extractVueScript(String source) {
        int open = source.indexOf("<script");
        if (open < 0) {
            return null;
        }
        int tagEnd = source.indexOf('>', open);
        if (tagEnd < 0) {
            return null;
        }
        int close = source.indexOf("</script>", tagEnd);
        if (close < 0) {
            return null;
        }
        int lineOffset = 0;
        for (int i = 0; i < tagEnd + 1; i++) {
            if (source.charAt(i) == '\n') {
                lineOffset++;
            }
        }
        return new int[] {lineOffset, tagEnd + 1, close};
    }

    private static String languageName(TSLanguage language) {
        if (language == JAVA) {
            return "java";
        }
        if (language == TYPESCRIPT) {
            return "typescript";
        }
        if (language == BASH) {
            return "bash";
        }
        return "javascript";
    }

    private record FileContext(
            String path, String language, byte[] bytes, int lineOffset, List<DevCodeSymbol> out) {}
}
