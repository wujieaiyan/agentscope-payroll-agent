package io.agentscope.examples.payroll.kb;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 扫描页 OCR：把整页图片交给 Tesseract，按 TSV 行结构重建文本并过滤低置信度词。 */
@Service
public class KbOcrService {

    private static final Logger log = LoggerFactory.getLogger(KbOcrService.class);
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    private final boolean enabled;
    private final String command;
    private final String tessdataDir;
    private final String defaultLanguages;
    private final int dpi;
    private final int timeoutSeconds;
    private final int maxPages;
    private volatile Boolean available;

    public KbOcrService(
            @Value("${payroll.kb.ocr.enabled:true}") boolean enabled,
            @Value("${payroll.kb.ocr.command:tesseract}") String command,
            @Value("${payroll.kb.ocr.tessdata-dir:}") String tessdataDir,
            @Value("${payroll.kb.ocr.languages:chi_sim+eng}") String defaultLanguages,
            @Value("${payroll.kb.ocr.dpi:300}") int dpi,
            @Value("${payroll.kb.ocr.timeout-seconds:120}") int timeoutSeconds,
            @Value("${payroll.kb.ocr.max-pages:0}") int maxPages) {
        this.enabled = enabled;
        this.command = command == null || command.isBlank() ? "tesseract" : command.strip();
        this.tessdataDir = tessdataDir == null ? "" : tessdataDir.strip();
        this.defaultLanguages =
                defaultLanguages == null || defaultLanguages.isBlank()
                        ? "chi_sim+eng"
                        : defaultLanguages.strip();
        this.dpi = Math.max(72, dpi);
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.maxPages = Math.max(0, maxPages);
    }

    public boolean enabled() {
        return enabled;
    }

    public int dpi() {
        return dpi;
    }

    /** 0 表示不限页数。 */
    public int maxPages() {
        return maxPages;
    }

    public boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean probed = probe();
        available = probed;
        if (!probed) {
            log.warn(
                    "[知识库] OCR 引擎不可用：command={}，扫描件将无法入库；请安装 Tesseract（含 chi_sim 语言包）"
                            + "或用 payroll.kb.ocr.command 指定可执行文件",
                    command);
        }
        return probed;
    }

    /** 知识库配置里的 zh/en 映射到 Tesseract 语言包名。 */
    public String resolveLanguages(List<String> configured) {
        Set<String> languages = new LinkedHashSet<>();
        if (configured != null) {
            for (String language : configured) {
                String mapped = mapLanguage(language);
                if (!mapped.isEmpty()) {
                    languages.add(mapped);
                }
            }
        }
        return languages.isEmpty() ? defaultLanguages : String.join("+", languages);
    }

    /**
     * 识别单页图片，返回按行重建的文本。
     *
     * @param minConfidence 词级置信度下限（0-1），低于该值的词会被剔除；全部低于时回退为不过滤。
     */
    public String recognize(BufferedImage image, String languages, double minConfidence)
            throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("kb-ocr-");
        try {
            Path png = workDir.resolve("page.png");
            if (!ImageIO.write(image, "png", png.toFile())) {
                throw new IOException("无法写出 OCR 临时图片");
            }
            Path stdout = workDir.resolve("out.tsv");
            Path stderr = workDir.resolve("err.txt");
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.add(png.toString());
            cmd.add("stdout");
            cmd.add("-l");
            cmd.add(languages);
            cmd.add("--psm");
            cmd.add("3");
            if (!tessdataDir.isEmpty()) {
                cmd.add("--tessdata-dir");
                cmd.add(tessdataDir);
            }
            cmd.add("tsv");
            Process process =
                    new ProcessBuilder(cmd)
                            .redirectOutput(stdout.toFile())
                            .redirectError(stderr.toFile())
                            .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OCR 超时（" + timeoutSeconds + "s）");
            }
            if (process.exitValue() != 0) {
                String message =
                        Files.exists(stderr)
                                ? Files.readString(stderr, StandardCharsets.UTF_8).strip()
                                : "";
                throw new IOException("OCR 进程退出码 " + process.exitValue() + "：" + message);
            }
            return rebuildText(Files.readString(stdout, StandardCharsets.UTF_8), minConfidence);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private boolean probe() {
        try {
            Process process =
                    new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static String mapLanguage(String language) {
        if (language == null) {
            return "";
        }
        return switch (language.strip().toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn", "zh_cn", "chinese", "simplified-chinese" -> "chi_sim";
            case "zh-tw", "zh_tw", "traditional-chinese" -> "chi_tra";
            case "en", "english" -> "eng";
            default -> language.strip();
        };
    }

    private static String rebuildText(String tsv, double minConfidence) {
        if (tsv == null || tsv.isBlank()) {
            return "";
        }
        List<Word> words = new ArrayList<>();
        for (String line : tsv.split("\\R")) {
            if (line.isBlank() || line.startsWith("level\t")) {
                continue;
            }
            String[] parts = line.split("\t", 12);
            if (parts.length < 12 || !"5".equals(parts[0])) {
                continue;
            }
            String text = parts[11];
            if (text == null || text.isBlank()) {
                continue;
            }
            words.add(
                    new Word(
                            intOf(parts[2]),
                            intOf(parts[3]),
                            intOf(parts[4]),
                            doubleOf(parts[10]),
                            text.strip()));
        }
        if (words.isEmpty()) {
            return "";
        }
        double threshold = Math.max(0, Math.min(1, minConfidence)) * 100;
        List<Word> kept = words.stream().filter(word -> word.confidence() >= threshold).toList();
        if (kept.isEmpty()) {
            kept = words;
        }
        StringBuilder text = new StringBuilder();
        String currentLine = null;
        String previous = null;
        for (Word word : kept) {
            String lineKey = word.block() + "/" + word.par() + "/" + word.line();
            if (!lineKey.equals(currentLine)) {
                if (currentLine != null) {
                    text.append('\n');
                }
                currentLine = lineKey;
                previous = null;
            }
            if (previous != null && needsSpace(previous, word.text())) {
                text.append(' ');
            }
            text.append(word.text());
            previous = word.text();
        }
        return text.toString();
    }

    private static int intOf(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double doubleOf(String value) {
        try {
            return Double.parseDouble(value.strip());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 只在两个 ASCII 词相邻时补空格，避免中文被切成带空格的碎片。 */
    private static boolean needsSpace(String previous, String next) {
        if (previous.isEmpty() || next.isEmpty()) {
            return false;
        }
        char left = previous.charAt(previous.length() - 1);
        char right = next.charAt(0);
        return isAsciiWordChar(left) && isAsciiWordChar(right);
    }

    private static boolean isAsciiWordChar(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 临时文件清理失败不影响识别结果。
                                }
                            });
        } catch (IOException ignored) {
            // 目录已不存在或不可读。
        }
    }

    private record Word(int block, int par, int line, double confidence, String text) {
    }
}
