package io.agentscope.payroll.parser.ocr;

/** OCR 档位链配置；enabled/command/tessdata 等 Tesseract 基础项仍由 KbOcrService 读取。 */
public class OcrEngineProperties {

    /** tesseract | rapidocr | chain；chain 按 chain-order 依次尝试。 */
    private String engine = "chain";

    /** 主档在前；每档结果先过质量门，未通过再降级。 */
    private String chainOrder = "rapidocr,tesseract";

    /** 内容哈希缓存条数；同图重识别不重复推理。 */
    private int cacheSize = 32;

    /** 连续失败达到阈值后熔断该档位。 */
    private int breakerFailures = 3;

    private int breakerCooldownSeconds = 60;

    private final QualityGate qualityGate = new QualityGate();
    private final RapidOcr rapidocr = new RapidOcr();

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getChainOrder() {
        return chainOrder;
    }

    public void setChainOrder(String chainOrder) {
        this.chainOrder = chainOrder;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public int getBreakerFailures() {
        return breakerFailures;
    }

    public void setBreakerFailures(int breakerFailures) {
        this.breakerFailures = breakerFailures;
    }

    public int getBreakerCooldownSeconds() {
        return breakerCooldownSeconds;
    }

    public void setBreakerCooldownSeconds(int breakerCooldownSeconds) {
        this.breakerCooldownSeconds = breakerCooldownSeconds;
    }

    public QualityGate getQualityGate() {
        return qualityGate;
    }

    public RapidOcr getRapidocr() {
        return rapidocr;
    }

    public static class QualityGate {
        private int minChars = 8;
        private double minAlnumRatio = 0.5;
        private double minCjkRatio = 0;
        private double minConfidence = 0;

        public int getMinChars() {
            return minChars;
        }

        public void setMinChars(int minChars) {
            this.minChars = minChars;
        }

        public double getMinAlnumRatio() {
            return minAlnumRatio;
        }

        public void setMinAlnumRatio(double minAlnumRatio) {
            this.minAlnumRatio = minAlnumRatio;
        }

        public double getMinCjkRatio() {
            return minCjkRatio;
        }

        public void setMinCjkRatio(double minCjkRatio) {
            this.minCjkRatio = minCjkRatio;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }
    }

    public static class RapidOcr {
        private boolean enabled = true;
        private String modelDir = "";
        private int timeoutSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModelDir() {
            return modelDir;
        }

        public void setModelDir(String modelDir) {
            this.modelDir = modelDir;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
