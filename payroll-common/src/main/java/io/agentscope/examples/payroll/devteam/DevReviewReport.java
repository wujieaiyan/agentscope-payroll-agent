package io.agentscope.examples.payroll.devteam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码审查报告（P1-7）：固定检查清单 + 逐文件结论，阻断项必须带 {@code 文件:行号} 证据。
 *
 * <p>报告由审查角色输出，解析结果决定任务成败：结构不完整或缺少证据直接失败，
 * 存在阻断项时任务失败并打回开发，补丁不得进入 G2。结论同时供审批摘要引用。
 */
public record DevReviewReport(String text, List<Finding> findings, List<String> violations) {

    public enum Verdict {
        PASS("通过"),
        SUGGEST("建议"),
        BLOCK("阻断");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Verdict of(String label) {
            for (Verdict verdict : values()) {
                if (verdict.label.equals(label)) {
                    return verdict;
                }
            }
            return null;
        }
    }

    /** 逐文件结论：文件路径 + 行号 + 说明。 */
    public record Finding(Verdict verdict, String path, int line, String note) {

        public String evidence() {
            return path + ":" + line;
        }
    }

    /** 固定检查清单：契约一致性/正确性/安全边界/测试覆盖。 */
    public static final List<String> CHECKLIST =
            List.of("契约一致性", "正确性", "安全边界", "测试覆盖");

    private static final Pattern FINDING =
            Pattern.compile(
                    "^[-*]\\s*\\[(通过|建议|阻断)]\\s*(.+?):(\\d+)\\s*(?:[—–\\-:：]\\s*)?(.*)$");
    private static final Pattern CHECKBOX = Pattern.compile("^[-*]\\s*\\[\\s*]");
    private static final int MAX_FINDINGS = 100;
    private static final int MAX_DUPLICATES = 5;

    public DevReviewReport {
        text = text == null ? "" : text;
        findings = findings == null ? List.of() : List.copyOf(findings);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** 审查任务提示词使用的模板，字段与解析规则一致。 */
    public static String template() {
        return """
                ## 审查报告
                ### 检查清单
                - [ ] 契约一致性
                - [ ] 正确性
                - [ ] 安全边界
                - [ ] 测试覆盖
                ### 逐文件结论
                - [通过] backend/server.js:12 — 路由参数校验完整，无越权访问
                - [建议] backend/recommend.js:20 — 关键词匹配可补充空数组边界用例
                - [阻断] backend/catalog.js:8 — 商品价格未做类型校验，非法输入会污染排序
                ### 总结
                整体结论、未关闭阻断项与需要人工确认的风险。
                格式要求：每条结论一行，阻断/建议/通过 + 文件:行号 + 说明；缺少证据视为不完整。
                """;
    }

    public static DevReviewReport parse(String text) {
        String value = text == null ? "" : text;
        List<Finding> findings = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (String raw : value.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || CHECKBOX.matcher(line).find()) {
                continue;
            }
            if (!(line.startsWith("- [") || line.startsWith("* ["))) {
                continue;
            }
            Matcher matcher = FINDING.matcher(line);
            if (!matcher.matches()) {
                violations.add("结论行缺少 文件:行号 证据：" + line);
                continue;
            }
            Verdict verdict = Verdict.of(matcher.group(1));
            String note = matcher.group(4) == null ? "" : matcher.group(4).strip();
            findings.add(
                    new Finding(verdict, matcher.group(2).strip(), Integer.parseInt(matcher.group(3)), note));
        }
        if (value.isBlank()) {
            violations.add("审查报告为空");
        }
        for (String item : CHECKLIST) {
            if (!value.contains(item)) {
                violations.add("缺少检查项：" + item);
            }
        }
        if (findings.isEmpty()) {
            violations.add("缺少逐文件结论（格式：- [通过|建议|阻断] 文件:行号 — 说明）");
        }
        if (findings.size() > MAX_FINDINGS) {
            violations.add("逐文件结论条数异常（" + findings.size() + " 条），疑似重复输出");
        }
        Set<String> distinct = new HashSet<>();
        int duplicates = 0;
        for (Finding finding : findings) {
            if (!distinct.add(finding.verdict() + "|" + finding.evidence() + "|" + finding.note())) {
                duplicates++;
            }
        }
        if (duplicates > MAX_DUPLICATES) {
            violations.add("审查报告存在重复结论（" + duplicates + " 条重复），疑似循环输出");
        }
        for (Finding finding : findings) {
            if (finding.verdict() == Verdict.BLOCK && finding.note().isBlank()) {
                violations.add("阻断项缺少说明：" + finding.evidence());
            }
        }
        return new DevReviewReport(value, findings, violations);
    }

    public List<Finding> blocking() {
        return findings.stream().filter(finding -> finding.verdict() == Verdict.BLOCK).toList();
    }

    public int count(Verdict verdict) {
        return (int) findings.stream().filter(finding -> finding.verdict() == verdict).count();
    }

    /** 一句话结论，供任务结果与 G2 摘要引用。 */
    public String summary() {
        return "审查结论：阻断 "
                + count(Verdict.BLOCK)
                + "、建议 "
                + count(Verdict.SUGGEST)
                + "、通过 "
                + count(Verdict.PASS);
    }

    /** 检查清单驱动下的阻断项摘要（含文件:行号），用于打回原因。 */
    public String blockingSummary() {
        if (blocking().isEmpty()) {
            return "无阻断项";
        }
        List<String> evidence = new ArrayList<>();
        for (Finding finding : blocking()) {
            evidence.add(finding.evidence() + " — " + finding.note());
        }
        return String.join("；", evidence);
    }
}
