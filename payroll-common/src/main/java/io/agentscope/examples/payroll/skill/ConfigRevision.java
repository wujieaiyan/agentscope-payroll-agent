package io.agentscope.examples.payroll.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 配置文档内容指纹，用于并发写入校验（HTTP ETag 语义）。 */
final class ConfigRevision {

    /** 文档不存在（空内容）的指纹。 */
    static final String ABSENT = revisionOf("");

    static String revisionOf(String raw) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ConfigRevision() {}
}
