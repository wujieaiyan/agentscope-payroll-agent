package io.agentscope.examples.payroll.kb.parse;

import java.util.Locale;

/** 解析链路配置：进程内联或独立解析服务，供 Web 与 parser 共享同一组环境变量。 */
public class KbParseSettings {

    public enum Mode {
        INLINE,
        REMOTE
    }

    private Mode mode = Mode.INLINE;
    private String url = "http://parser:8300";
    private String token = "";
    private int webConcurrency = 2;
    private int maxPages = 200;
    private long timeoutMs = 120_000L;
    private int maxRetries = 2;
    private long retryBackoffMs = 30_000L;
    private int connectTimeoutMs = 5_000;
    private String artifactDir = "parsed";
    private int queueWaitMs = 2_000;
    private int maxSkewSeconds = 120;
    private int workerThreads = 2;
    private String kbDir = "data/kb-uploads";

    public Mode mode() {
        return mode;
    }

    public void setMode(String value) {
        if (value == null || value.isBlank()) {
            this.mode = Mode.INLINE;
            return;
        }
        try {
            this.mode = Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("payroll.kb.parse.mode 只能是 inline 或 remote：" + value);
        }
    }

    public boolean remote() {
        return mode == Mode.REMOTE;
    }

    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String token() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int webConcurrency() {
        return webConcurrency;
    }

    public void setWebConcurrency(int webConcurrency) {
        this.webConcurrency = webConcurrency;
    }

    public int maxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long retryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String artifactDir() {
        return artifactDir;
    }

    public void setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
    }

    public int queueWaitMs() {
        return queueWaitMs;
    }

    public void setQueueWaitMs(int queueWaitMs) {
        this.queueWaitMs = queueWaitMs;
    }

    public int maxSkewSeconds() {
        return maxSkewSeconds;
    }

    public void setMaxSkewSeconds(int maxSkewSeconds) {
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String kbDir() {
        return kbDir;
    }

    public void setKbDir(String kbDir) {
        this.kbDir = kbDir;
    }
}
