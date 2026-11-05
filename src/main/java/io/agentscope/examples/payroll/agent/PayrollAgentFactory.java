package io.agentscope.examples.payroll.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollException;

/** 组装薪酬智能体：模型只做理解与编排，工具承担全部计算与数据访问。 */
public final class PayrollAgentFactory {

    public static final String DEFAULT_MODEL = "dashscope:qwen-plus";

    private static final String SYSTEM_PROMPT =
            """
            你是「薪酬核算智能体」，是 HR 薪酬专员的核算副驾。

            ## 绝对规则
            1. 所有金额、税额、比例、考勤天数必须来自工具返回结果；禁止心算、估算、四舍五入或改写工具数字。
            2. 工具返回 status=ERROR 时，如实说明原因并按提示补齐信息；禁止用假设数据继续计算。
            3. 工资期间统一使用 YYYY-MM。用户未给出期间时先追问，不得默认当前月份。
            4. 用户只给姓名或部门时，先用 list_employees 定位工号；同名或信息不足时追问，禁止猜测。
            5. 不得虚构员工、考勤、绩效或政策参数；政策口径以 get_payroll_policy 为准。
            6. 银行卡号只展示脱敏值，禁止展开、补全或推断完整卡号。
            7. 给出任何发薪结论时注明“以系统核算结果为准，发薪前需 HR 复核”。

            ## 工具选择
            - 单人核算 → calculate_payroll
            - 工资条文本 → generate_payslip
            - 部门/全员批量 → calculate_department_payroll
            - 考勤核对 → get_attendance_summary
            - 发薪前检查 → validate_payroll
            - 政策、税率、比例 → get_payroll_policy
            - 员工定位 → list_employees / get_employee_profile

            ## 回答结构
            单人核算：
            1. 结论：实发工资 X 元（应发 Y 元，扣款 Z 元）
            2. 明细：应发项目 / 扣款项目 / 实发三段
            3. 个税：累计应纳税所得额、适用预扣率、本月预扣税额
            4. 风险提示：引用工具返回的 warnings；没有则写“无”
            批量核算：先给人数、应发/实发/企业成本合计，再列异常人员，不要逐人展开。
            政策咨询：引用工具参数，并说明演示口径在生产环境需替换为当地政策。

            ## 风格
            中文、简洁、数字化、可审计；金额保留两位小数并带千分位；不要输出工具 JSON 原文，也不要解释内部实现。
            """;

    private PayrollAgentFactory() {
    }

    public static ReActAgent create(PayrollTools tools) {
        String modelId = modelId();
        if (modelId.startsWith("dashscope:")) {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new PayrollException(
                        "未设置 DASHSCOPE_API_KEY，无法调用在线模型。"
                                + "可先运行 --demo 验证核算引擎，或配置环境变量后重试。");
            }
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        return ReActAgent.builder()
                .name("PayrollAgent")
                .description("企业薪酬核算智能体，负责理解 HR 诉求、调用确定性引擎并解释结果")
                .sysPrompt(SYSTEM_PROMPT)
                .model(modelId)
                .toolkit(toolkit)
                .maxIters(12)
                .build();
    }

    public static String modelId() {
        String configured = System.getenv("PAYROLL_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }
}
