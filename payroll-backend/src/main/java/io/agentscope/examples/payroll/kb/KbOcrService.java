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

/** 扫描页 OCR：把整页图片交给 Tesseract，按 TSV 行结构重建文本；低置信度词只做标记。 */
@Service
public class KbOcrService {

    private static final Logger log = LoggerFactory.getLogger(KbOcrService.class);
    private static final int PROBE_TIMEOUT_SECONDS = 10;
    /** 探测失败只短时缓存：装好引擎后无需重启即可恢复。 */
    private static final long PROBE_FAILURE_TTL_MS = 60_000L;
    /** TSV 置信度为 0-100；低于该值记入 lowConfidenceWords，供质量门与溯源参考。 */
    private static final double LOW_CONFIDENCE_PERCENT = 60;

    private final boolean enabled;
    private final String command;
    private final String tessdataDir;
    private final String defaultLanguages;
    private final int dpi;
    private final int pageTimeoutSeconds;
    private final int maxPages;
    private final int concurrency;
    private final int docBudgetSeconds;
    private volatile Boolean available;
    private volatile long reprobeAt;
    private volatile String probeFailureType;

    public KbOcrService(
            @Value("${payroll.kb.ocr.enabled:true}") boolean enabled,
            @Value("${payroll.kb.ocr.command:tesseract}") String command,
            @Value("${payroll.kb.ocr.tessdata-dir:}") String tessdataDir,
            @Value("${payroll.kb.ocr.languages:chi_sim+eng}") String defaultLanguages,
            @Value("${payroll.kb.ocr.dpi:200}") int dpi,
            @Value("${payroll.kb.ocr.page-timeout-seconds:120}") int pageTimeoutSeconds,
            @Value("${payroll.kb.ocr.max-pages:200}") int maxPages,
            @Value("${payroll.kb.ocr.concurrency:2}") int concurrency,
            @Value("${payroll.kb.ocr.timeout-seconds:600}") int docBudgetSeconds) {
        this.enabled = enabled;
        this.command = command == null || command.isBlank() ? "tesseract" : command.strip();
        this.tessdataDir = tessdataDir == null ? "" : tessdataDir.strip();
        this.defaultLanguages =
                defaultLanguages == null || defaultLanguages.isBlank()
                        ? "chi_sim+eng"
                        : defaultLanguages.strip();
        this.dpi = Math.max(72, dpi);
        this.pageTimeoutSeconds = Math.max(10, pageTimeoutSeconds);
        this.maxPages = Math.max(0, maxPages);
        this.concurrency = Math.max(1, Math.min(concurrency, 4));
        this.docBudgetSeconds = Math.max(30, docBudgetSeconds);
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

    /** 单页识别超时，供外部档位（ImageTextExtractor）复用同一配置。 */
    public int pageTimeoutSeconds() {
        return pageTimeoutSeconds;
    }

    /** 单文档 OCR 总预算，超出后不再提交新的扫描页。 */
    public int docBudgetSeconds() {
        return docBudgetSeconds;
    }

    public int concurrency() {
        return concurrency;
    }

    /** 最近一次探测失败的异常类型，供健康检查与日志定位。 */
    public String probeFailureType() {
        return probeFailureType;
    }

    public boolean available() {
        Boolean cached = available;
        long now = System.currentTimeMillis();
        if (cached != null && (cached || now < reprobeAt)) {
            return cached;
        }
        synchronized (this) {
            if (available != null && (available || System.currentTimeMillis() < reprobeAt)) {
                return available;
            }
            ProbeResult result = probe();
            available = result.ok();
            if (result.ok()) {
                reprobeAt = 0;
                probeFailureType = null;
            } else {
                probeFailureType = result.failureType();
                reprobeAt = System.currentTimeMillis() + PROBE_FAILURE_TTL_MS;
                log.warn(
                        "[知识库] OCR 引擎不可用（{}）：command={}，扫描件将无法入库；"
                                + "请安装 Tesseract（含 chi_sim 语言包）或用 payroll.kb.ocr.command 指定可执行文件",
                        result.failureType(),
                        command);
            }
            return available;
        }
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
     * 识别单页图片；词级置信度只做统计标记，不再丢弃文本，是否可用交给质量门判定。
     */
    public OcrText recognize(BufferedImage image, String languages)
            throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("kb-ocr-");
        try {
            Path png = workDir.resolve("page.png");
            if (!ImageIO.write(image, "png", png.toFile())) {
                throw new IOException("无法写出 OCR 临时图片");
            }
            return recognizeFile(png, languages);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** 直接识别磁盘上的图片，避免调用方已落盘时再做一次编码。 */
    public OcrText recognizeFile(Path image, String languages)
            throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("kb-ocr-");
        try {
            Path stdout = workDir.resolve("out.tsv");
            Path stderr = workDir.resolve("err.txt");
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.add(image.toString());
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
            try {
                if (!process.waitFor(pageTimeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("OCR 超时（" + pageTimeoutSeconds + "s）");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                throw e;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            if (process.exitValue() != 0) {
                String message =
                        Files.exists(stderr)
                                ? Files.readString(stderr, StandardCharsets.UTF_8).strip()
                                : "";
                throw new IOException("OCR 进程退出码 " + process.exitValue() + "：" + message);
            }
            return rebuildText(Files.readString(stdout, StandardCharsets.UTF_8));
        } finally {
            deleteQuietly(workDir);
        }
    }

    private ProbeResult probe() {
        try {
            Process process =
                    new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProbeResult(false, "TIMEOUT");
            }
            return process.exitValue() == 0
                    ? new ProbeResult(true, null)
                    : new ProbeResult(false, "EXIT_CODE_" + process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "INTERRUPTED");
        } catch (IOException e) {
            return new ProbeResult(false, e.getClass().getSimpleName());
        }
    }

    private record ProbeResult(boolean ok, String failureType) {
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

    private static OcrText rebuildText(String tsv) {
        if (tsv == null || tsv.isBlank()) {
            return new OcrText("", 0, 0, 0);
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
            return new OcrText("", 0, 0, 0);
        }
        StringBuilder text = new StringBuilder();
        String currentLine = null;
        String previous = null;
        double total = 0;
        int lowConfidence = 0;
        for (Word word : words) {
            total += word.confidence();
            if (word.confidence() < LOW_CONFIDENCE_PERCENT) {
                lowConfidence++;
            }
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
        double confidence = total / words.size() / 100.0;
        return new OcrText(text.toString(), words.size(), lowConfidence, confidence);
    }

    /** Tesseract 词级置信度统计结果；text 保留全部词，不再按置信度裁剪。 */
    public record OcrText(String text, int words, int lowConfidenceWords, double confidence) {
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
