package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P1-7 审查报告：模板自洽、逐文件结论解析、证据与阻断校验。 */
class DevReviewReportTest {

    @Test
    @DisplayName("模板包含四项固定检查与三类结论示例，且模板本身可解析")
    void templateIsSelfConsistent() {
        String template = DevReviewReport.template();
        for (String item : DevReviewReport.CHECKLIST) {
            assertTrue(template.contains(item), item);
        }

        DevReviewReport report = DevReviewReport.parse(template);

        assertTrue(report.violations().isEmpty(), report.violations().toString());
        assertEquals(3, report.findings().size());
        assertEquals(1, report.count(DevReviewReport.Verdict.BLOCK));
        assertEquals(1, report.count(DevReviewReport.Verdict.SUGGEST));
        assertEquals(1, report.count(DevReviewReport.Verdict.PASS));
    }

    @Test
    @DisplayName("完整报告解析出逐文件结论，复选框行不参与解析")
    void parsesFindingsAndIgnoresCheckboxes() {
        DevReviewReport report =
                DevReviewReport.parse(
                        report(
                                "- [通过] payroll-common/src/A.java:12 — 契约不变",
                                "- [建议] payroll-agent/src/B.java:30 — 建议补日志"));

        assertTrue(report.violations().isEmpty(), report.violations().toString());
        assertEquals(2, report.findings().size());
        assertEquals("审查结论：阻断 0、建议 1、通过 1", report.summary());
        assertEquals("无阻断项", report.blockingSummary());
        assertEquals("payroll-agent/src/B.java:30", report.findings().get(1).evidence());
    }

    @Test
    @DisplayName("阻断项携带 文件:行号 证据并拼入打回摘要")
    void detectsBlockingWithEvidence() {
        DevReviewReport report =
                DevReviewReport.parse(
                        report("- [阻断] payroll-common/src/A.java:12 — 空指针未处理"));

        assertTrue(report.violations().isEmpty(), report.violations().toString());
        assertEquals(1, report.blocking().size());
        String summary = report.blockingSummary();
        assertTrue(summary.contains("payroll-common/src/A.java:12"));
        assertTrue(summary.contains("空指针未处理"));
    }

    @Test
    @DisplayName("结论行缺少 文件:行号 记为违规")
    void rejectsFindingWithoutEvidence() {
        DevReviewReport report =
                DevReviewReport.parse(report("- [通过] 契约一致性没问题"));

        assertTrue(
                report.violations().stream().anyMatch(item -> item.contains("缺少 文件:行号 证据")),
                report.violations().toString());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    @DisplayName("空报告或缺检查项、缺结论时给出可定位的违规项")
    void rejectsIncompleteReport() {
        DevReviewReport empty = DevReviewReport.parse("");
        assertTrue(empty.violations().contains("审查报告为空"));
        assertTrue(empty.violations().contains("缺少检查项：契约一致性"));
        assertTrue(empty.violations().contains("缺少检查项：测试覆盖"));
        assertTrue(
                empty.violations().stream().anyMatch(item -> item.startsWith("缺少逐文件结论")),
                empty.violations().toString());

        DevReviewReport missingChecklist =
                DevReviewReport.parse("- [通过] src/A.java:1 — 无问题");
        assertTrue(missingChecklist.violations().contains("缺少检查项：正确性"));
        assertFalse(missingChecklist.findings().isEmpty());
    }

    @Test
    @DisplayName("阻断项缺少说明单独计为违规")
    void rejectsBlockingWithoutNote() {
        DevReviewReport report = DevReviewReport.parse(report("- [阻断] src/A.java:10"));

        assertTrue(
                report.violations().contains("阻断项缺少说明：src/A.java:10"),
                report.violations().toString());
    }

    @Test
    @DisplayName("同一结论重复刷屏或结论条数异常记为违规")
    void rejectsRepeatedFindings() {
        String line = "- [通过] backend/personalize.js:12 — 实现完整";
        DevReviewReport repeated =
                DevReviewReport.parse(report(line, line, line, line, line, line, line));

        assertTrue(
                repeated.violations().stream().anyMatch(item -> item.contains("疑似循环输出")),
                repeated.violations().toString());

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            many.append("- [通过] backend/personalize.js:")
                    .append(i + 1)
                    .append(" — 第 ")
                    .append(i + 1)
                    .append(" 行符合契约\n");
        }
        DevReviewReport oversized = DevReviewReport.parse(report(many.toString().strip()));
        assertTrue(
                oversized.violations().stream().anyMatch(item -> item.contains("条数异常")),
                oversized.violations().toString());
    }

    private static String report(String... findings) {
        return """
                ### 检查清单
                - [ ] 契约一致性
                - [ ] 正确性
                - [ ] 安全边界
                - [ ] 测试覆盖
                ### 逐文件结论
                """
                + String.join("\n", findings)
                + """

                ### 总结
                无
                """;
    }
}
