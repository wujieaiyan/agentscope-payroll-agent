package io.agentscope.examples.payroll.model;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型出网策略（SSRF）：只允许 http/https；私网与回环默认拒绝，本机 Ollama 端口默认放行；
 * 可用 {@code PAYROLL_MODEL_OUTBOUND_ALLOW} 追加白名单（逗号分隔 host 或 host:port）。
 *
 * <p>连接测试与推理共用同一策略，避免「测试通过、推理被拦」。
 */
public final class ModelOutboundPolicy {

    public static final String ENV_ALLOW = "PAYROLL_MODEL_OUTBOUND_ALLOW";
    public static final int OLLAMA_PORT = 11_434;

    private static final Logger log = LoggerFactory.getLogger(ModelOutboundPolicy.class);

    private final List<String> allowList;
    private final BiConsumer<URI, String> denialSink;

    public ModelOutboundPolicy(List<String> allowList) {
        this(allowList, null);
    }

    public ModelOutboundPolicy(List<String> allowList, BiConsumer<URI, String> denialSink) {
        this.allowList = allowList == null ? List.of() : List.copyOf(allowList);
        this.denialSink = denialSink;
    }

    /** 追加拒绝回调（审计用）；返回新实例，不改动已有共享策略。 */
    public ModelOutboundPolicy withDenialSink(BiConsumer<URI, String> sink) {
        return new ModelOutboundPolicy(allowList, sink);
    }

    public static ModelOutboundPolicy fromEnvironment() {
        List<String> entries = new ArrayList<>();
        String value = System.getenv(ENV_ALLOW);
        if (value != null && !value.isBlank()) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    entries.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ModelOutboundPolicy(entries);
    }

    public void check(String baseUrl) {
        try {
            check(URI.create(baseUrl));
        } catch (IllegalArgumentException e) {
            throw new ModelConfigException(
                    ModelConfigException.OUTBOUND_BLOCKED, "模型地址不是合法 URL：" + baseUrl);
        }
    }

    public void check(URI uri) {
        String violation = violation(uri);
        if (violation != null) {
            log.warn("[Model] 拒绝出网目标 {}：{}", uri, violation);
            if (denialSink != null) {
                try {
                    denialSink.accept(uri, violation);
                } catch (RuntimeException e) {
                    log.warn("[Model] 记录出网拒绝审计失败：{}", e.getMessage());
                }
            }
            throw new ModelConfigException(ModelConfigException.OUTBOUND_BLOCKED, violation);
        }
    }

    /** 返回拒绝原因；放行时返回 null。 */
    public String violation(URI uri) {
        if (uri == null) {
            return "缺少模型地址";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "只允许 http / https 协议，当前：" + scheme;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "模型地址缺少主机名";
        }
        int port = uri.getPort();
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (allowed(normalizedHost, port)) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            return "无法解析模型主机：" + host;
        }
        for (InetAddress address : addresses) {
            if (!isLocalOllamaPort(address, port)
                    && (address.isLoopbackAddress()
                            || address.isSiteLocalAddress()
                            || address.isLinkLocalAddress()
                            || address.isAnyLocalAddress())) {
                return "按默认策略禁止访问私网/回环地址 " + host
                        + "，如确需访问请加入 " + ENV_ALLOW;
            }
        }
        return null;
    }

    private boolean allowed(String host, int port) {
        for (String entry : allowList) {
            if (entry.equals(host)) {
                return true;
            }
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            int entryPort;
            try {
                entryPort = Integer.parseInt(entry.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (entry.substring(0, colon).equals(host)
                    && (port == entryPort || (port < 0 && entryPort == 80))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalOllamaPort(InetAddress address, int port) {
        int effectivePort = port < 0 ? 80 : port;
        return address.isLoopbackAddress() && effectivePort == OLLAMA_PORT;
    }
}
