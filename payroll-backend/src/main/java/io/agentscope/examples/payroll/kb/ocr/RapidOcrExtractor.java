package io.agentscope.examples.payroll.kb.ocr;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 主档：RapidOCR（PP-OCRv4 / ONNX 原生库）。
 *
 * <p>依赖通过 Maven profile {@code rapidocr} 引入，因此这里用反射隔离第三方 API；
 * 默认构建没有原生库时 {@link #available()} 返回 false，自动回落到降级档。
 */
@Component
public class RapidOcrExtractor implements ImageTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(RapidOcrExtractor.class);
    private static final String ENGINE_CLASS = "io.github.mymonstercat.ocr.InferenceEngine";
    private static final String MODEL_CLASS = "io.github.mymonstercat.Model";
    private static final String MODEL_CONSTANT = "ONNX_PPOCR_V4";
    private static final double LOW_CONFIDENCE = 0.6;

    private final boolean enabled;
    private final String modelDir;
    private final int timeoutSeconds;
    private final boolean classesPresent;
    private final boolean platformSupported;
    private final Object lock = new Object();

    private volatile Object engine;
    private volatile String initError;

    public RapidOcrExtractor(
            @Value("${payroll.kb.ocr.rapidocr.enabled:true}") boolean enabled,
            @Value("${payroll.kb.ocr.rapidocr.model-dir:}") String modelDir,
            @Value("${payroll.kb.ocr.rapidocr.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.modelDir = modelDir == null ? "" : modelDir.strip();
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.classesPresent = classPresent(ENGINE_CLASS) && classPresent(MODEL_CLASS);
        this.platformSupported = isWindowsX64();
        if (enabled && classesPresent && platformSupported && !this.modelDir.isEmpty()) {
            pinModelDir(this.modelDir);
        }
    }

    @Override
    public String id() {
        return "rapidocr";
    }

    @Override
    public String model() {
        return "PP-OCRv4";
    }

    @Override
    public String version() {
        return "1.2.2";
    }

    @Override
    public boolean available() {
        return enabled && classesPresent && platformSupported && initError == null;
    }

    @Override
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public Result extract(Path image, String languages) throws Exception {
        Object instance = engine();
        long started = System.currentTimeMillis();
        Method runOcr = instance.getClass().getMethod("runOcr", String.class);
        Object result;
        try {
            result = runOcr.invoke(instance, image.toString());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("RapidOCR 识别失败：" + cause.getMessage(), cause);
        }
        long latency = System.currentTimeMillis() - started;
        List<?> blocks = (List<?>) result.getClass().getMethod("getTextBlocks").invoke(result);
        StringBuilder text = new StringBuilder();
        double total = 0;
        int words = 0;
        int lowConfidence = 0;
        Method getText = null;
        Method getScore = null;
        for (Object block : blocks) {
            if (getText == null) {
                getText = block.getClass().getMethod("getText");
                getScore = block.getClass().getMethod("getBoxScore");
            }
            String line = (String) getText.invoke(block);
            if (line == null || line.isBlank()) {
                continue;
            }
            double score = ((Number) getScore.invoke(block)).doubleValue();
            total += score;
            words++;
            if (score < LOW_CONFIDENCE) {
                lowConfidence++;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.strip());
        }
        double confidence = words == 0 ? 0 : total / words;
        return new Result(text.toString(), confidence, latency, words, lowConfidence);
    }

    private Object engine() throws Exception {
        Object ready = engine;
        if (ready != null) {
            return ready;
        }
        synchronized (lock) {
            if (engine != null) {
                return engine;
            }
            if (initError != null) {
                throw new IllegalStateException(initError);
            }
            try {
                Class<?> modelClass = Class.forName(MODEL_CLASS);
                Object model = modelClass.getField(MODEL_CONSTANT).get(null);
                Class<?> engineClass = Class.forName(ENGINE_CLASS);
                Method getInstance = engineClass.getMethod("getInstance", modelClass);
                engine = getInstance.invoke(null, model);
                log.info("[OCR] RapidOCR 引擎已初始化，模型 {}", model);
                return engine;
            } catch (Exception e) {
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                        ? e.getCause()
                        : e;
                initError = "RapidOCR 初始化失败：" + cause.getMessage();
                throw new IllegalStateException(initError, cause);
            }
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, RapidOcrExtractor.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && ("amd64".equals(arch) || "x86_64".equals(arch));
    }

    /**
     * rapidocr 0.0.7 在 JarFileUtil 静态初始化时固化 java.io.tmpdir，没有单独的模型目录参数。
     * 启动时先临时改写 tmpdir 再触发类初始化，使模型解压到可持久化目录；随后立即还原，避免影响其他临时文件。
     */
    private static void pinModelDir(String dir) {
        String previous = System.getProperty("java.io.tmpdir");
        try {
            Path target = Path.of(dir).toAbsolutePath();
            Files.createDirectories(target);
            System.setProperty("java.io.tmpdir", target.toString());
            Class.forName("io.github.mymonstercat.JarFileUtil", true, RapidOcrExtractor.class.getClassLoader());
            log.info("[OCR] RapidOCR 原生库与模型目录：{}/ocrJava", target);
        } catch (ClassNotFoundException e) {
            log.warn("[OCR] 未找到 RapidOCR 公共构件，模型目录配置不生效");
        } catch (Exception e) {
            log.warn("[OCR] 初始化 RapidOCR 模型目录失败：{}", e.getMessage());
        } finally {
            if (previous != null) {
                System.setProperty("java.io.tmpdir", previous);
            }
        }
    }
}
