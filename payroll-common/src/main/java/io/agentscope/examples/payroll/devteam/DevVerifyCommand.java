package io.agentscope.examples.payroll.devteam;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受限 runner 的校验命令（P1-3）：只允许构建/测试类的固定二进制，按 argv 直接执行，
 * 不经过 shell，因此不存在命令拼接；超时按命令分档，上限 30 分钟。
 */
public record DevVerifyCommand(
        String name, List<String> command, int timeoutSeconds, String image) {

    public static final Set<String> ALLOWED_BINARIES = Set.of("mvn", "npm", "node", "java");

    public static final int DEFAULT_TIMEOUT_SECONDS = 900;
    public static final int MAX_TIMEOUT_SECONDS = 1800;

    public DevVerifyCommand {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new PayrollException("校验命令缺少名称");
        }
        if (command == null || command.isEmpty()) {
            throw new PayrollException("校验命令缺少 argv：" + name);
        }
        List<String> argv = List.copyOf(command);
        String binary = argv.get(0).trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_BINARIES.contains(binary)) {
            throw new PayrollException(
                    "校验命令不在 allowlist：" + name + " -> " + argv.get(0));
        }
        for (String arg : argv) {
            if (arg == null || arg.isBlank()) {
                throw new PayrollException("校验命令含空参数：" + name);
            }
        }
        command = argv;
        timeoutSeconds =
                timeoutSeconds <= 0
                        ? DEFAULT_TIMEOUT_SECONDS
                        : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        image = image == null ? "" : image.trim();
    }
}
