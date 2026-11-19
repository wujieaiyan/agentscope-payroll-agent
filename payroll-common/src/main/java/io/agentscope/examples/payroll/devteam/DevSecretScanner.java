package io.agentscope.examples.payroll.devteam;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补丁明文凭据扫描（P0-6/A11）：只扫描 diff 的新增行，命中高置信度凭据即拒绝产出补丁。
 *
 * <p>宁可漏报不误报：只认私钥块、云厂商访问密钥、平台 token 与显式赋值的口令/密钥；
 * 占位符（含 example/your_/xxx/${...}/&lt;...&gt;）与注释掉的删除行不构成命中。
 */
public final class DevSecretScanner {

    public record Finding(String path, int line, String kind) {

        public String describe() {
            return path + " 新增第 " + line + " 行（" + kind + "）";
        }
    }

    private record Rule(String kind, Pattern pattern, int valueGroup) {

        boolean placeholder(String value) {
            return PLACEHOLDER.matcher(value).matches();
        }
    }

    private static final Pattern PLACEHOLDER =
            Pattern.compile(
                    "(?i).*(example|your[_-]?|placeholder|changeme|dummy|sample|redacted|\\$\\{|<[^>]*>|x{6,}).*");
    private static final String QUOTED_VALUE = "['\"]([^'\"]{16,})['\"]";

    private static final List<Rule> RULES =
            List.of(
                    new Rule(
                            "私钥块",
                            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                            0),
                    new Rule(
                            "AWS 访问密钥",
                            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
                            0),
                    new Rule(
                            "GitHub Token",
                            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
                            0),
                    new Rule(
                            "GitHub 细粒度 Token",
                            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}\\b"),
                            0),
                    new Rule(
                            "Slack Token",
                            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
                            0),
                    new Rule(
                            "sk 风格密钥",
                            Pattern.compile("\\bsk-[A-Za-z0-9]{32,}\\b"),
                            0),
                    new Rule(
                            "明文口令/密钥赋值",
                            Pattern.compile(
                                    "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|client[_-]?secret)\\b\\s*[:=]\\s*"
                                            + QUOTED_VALUE),
                            2));

    private DevSecretScanner() {}

    public static List<Finding> scan(String diff) {
        List<Finding> findings = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return findings;
        }
        String path = "unknown";
        int addedLine = 0;
        for (String raw : diff.split("\\R", -1)) {
            if (raw.startsWith("+++ b/")) {
                path = raw.substring(6).strip();
                addedLine = 0;
                continue;
            }
            if (raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("@@")) {
                continue;
            }
            if (raw.startsWith("-")) {
                continue;
            }
            addedLine++;
            String added = raw.startsWith("+") ? raw.substring(1) : raw;
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(added);
                if (!matcher.find()) {
                    continue;
                }
                if (rule.valueGroup() > 0 && rule.placeholder(matcher.group(rule.valueGroup()))) {
                    continue;
                }
                findings.add(new Finding(path, addedLine, rule.kind()));
                break;
            }
        }
        return findings;
    }
}
