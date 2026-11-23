package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 提示词资源缺失或安全红线被删改时，构建与测试必须直接失败。 */
class PromptLibraryTest {

    private static final List<String> PROMPTS =
            List.of(
                    "common-rules",
                    "skill-boot-rule",
                    "general-rules",
                    "kb-search-guidance",
                    "kb-config-guidance",
                    "pipeline-interaction-rules",
                    "calculator-responsibility",
                    "validator-responsibility",
                    "accountant-responsibility",
                    "kb-agent-responsibility",
                    "active-agent-responsibility",
                    "context-summarizer",
                    "intent-extractor",
                    "dev-task-rules",
                    "router-instruction",
                    "router-kb-section",
                    "router-creative-section",
                    "router-creative-no-kb-section",
                    "router-continuation-section",
                    "team-planner-system",
                    "team-intent-instruction");

    @Test
    void everyPromptResourceLoads() {
        for (String name : PROMPTS) {
            assertFalse(PromptLibrary.get(name).isBlank(), "提示词为空：" + name);
        }
    }

    @Test
    void payrollSafetyInvariantsStayInCommonRules() {
        String rules = PromptLibrary.get("common-rules");
        assertTrue(rules.contains("禁止心算"), "金额必须来自工具的规则被移除");
        assertTrue(rules.contains("脱敏"), "银行卡号脱敏规则被移除");
        assertTrue(rules.contains("total_deductions"), "扣款勾稽规则被移除");
        assertTrue(rules.contains("YYYY-MM"), "工资期间规则被移除");
    }

    @Test
    void placeholdersAreFormattedWithRuntimeValues() {
        assertTrue(
                PromptLibrary.get("skill-boot-rule")
                        .formatted("payroll:calculator")
                        .contains("load_skill_through_path"));
        String active =
                PromptLibrary.get("active-agent-responsibility").formatted("诗词助手", "写古诗词");
        assertTrue(active.contains("你是「诗词助手」"));
        assertTrue(active.contains("写古诗词"));
    }

    @Test
    void routerSectionsKeepLeadingBlankLine() {
        assertFalse(PromptLibrary.get("router-instruction").startsWith("\n"), "主指令不应有前导空行");
        for (String name :
                List.of(
                        "router-kb-section",
                        "router-creative-section",
                        "router-creative-no-kb-section",
                        "router-continuation-section")) {
            assertTrue(PromptLibrary.get(name).startsWith("\n"), "追加段丢失前导空行：" + name);
        }
    }

    @Test
    void continuationSectionFormatsFourRuntimeValues() {
        String text =
                PromptLibrary.get("router-continuation-section")
                        .formatted("写一首诗", "poetry-001", "灯火通明夜未央", "poetry-001");
        assertTrue(text.contains("上一轮用户问题：写一首诗"));
        assertTrue(text.contains("上一轮执行者：poetry-001"));
        assertTrue(text.contains("agent_id=\"poetry-001\""));
    }
}
