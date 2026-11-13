package io.agentscope.examples.payroll.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 语义缓存的作用域载体：Web 层把「部门 + 权限指纹」放进 ThreadLocal，
 * 执行线程在调用模型前读取，避免把登录态对象传遍对话链路。
 */
public final class SemanticCacheContext {

    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private SemanticCacheContext() {
    }

    public static void set(String scope) {
        SCOPE.set(scope == null ? "" : scope);
    }

    public static String scope() {
        String value = SCOPE.get();
        return value == null ? "" : value;
    }

    public static void clear() {
        SCOPE.remove();
    }

    /** 权限指纹：部门 + 排序后的权限集合哈希，权限不同不会互相命中。 */
    public static String fingerprint(Long departmentId, Set<String> permissions) {
        String sorted =
                permissions == null
                        ? ""
                        : permissions.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
        return "d=" + (departmentId == null ? 0L : departmentId) + "|p=" + sha256(sorted);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
