package io.agentscope.examples.payroll;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollIntent;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.Money;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.repository.JsonPayrollRepository;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayslipFormatter;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 薪酬智能体命令行入口。
 *
 * <pre>
 *   --demo                         离线演示：批量核算 + 工资条 + 校验（无需模型）
 *   --payroll EMP001 --period 2026-10   单人核算并打印工资条
 *   --department 研发部 --period 2026-10 部门批量核算
 *   --extract "帮我算下张伟10月的工资"      只做结构化意图抽取
 *   直接运行                        交互式对话
 * </pre>
 */
public final class PayrollApplication {

    private PayrollApplication() {
    }

    public static void main(String[] args) throws Exception {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                printHelp();
                return;
            }

            PayrollEngine engine =
                    new PayrollEngine(
                            JsonPayrollRepository.demo(), PayrollPolicy.demoPolicy());
            PayrollTools tools = new PayrollTools(engine);
            YearMonth period =
                    parsePeriod(
                            options.period != null
                                    ? options.period
                                    : engine.dataThroughPeriod().toString());

            if (options.demo) {
                runDemo(engine, period);
                return;
            }
            if (options.payrollId != null) {
                runSingle(engine, options.payrollId, period);
                return;
            }
            if (options.department != null) {
                runDepartment(engine, options.department, period);
                return;
            }

            try (ReActAgent agent = PayrollAgentFactory.create(tools)) {
                if (options.extractText != null) {
                    runExtraction(agent, options.extractText);
                } else {
                    runInteractive(agent);
                }
            }
        } catch (PayrollException e) {
            System.err.println("错误：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void runDemo(PayrollEngine engine, YearMonth period) {
        System.out.println("=".repeat(72));
        System.out.println("薪酬核算离线演示  期间：" + period + "（不调用大模型）");
        System.out.println("=".repeat(72));
        BatchPayrollResult batch = engine.calculateDepartment(null, period);
        printBatch(batch);
        if (!batch.records().isEmpty()) {
            PayrollResult first = batch.records().get(0);
            System.out.println(PayslipFormatter.format(first));
            printValidation(engine.validate(first.employeeId(), period));
        }
    }

    private static void runSingle(PayrollEngine engine, String employeeId, YearMonth period) {
        PayrollResult result = engine.calculate(employeeId, period);
        System.out.println(PayslipFormatter.format(result));
        printValidation(engine.validate(employeeId, period));
    }

    private static void runDepartment(
            PayrollEngine engine, String department, YearMonth period) {
        printBatch(engine.calculateDepartment(department, period));
    }

    private static void runExtraction(ReActAgent agent, String text) {
        Msg msg = agent.call(new UserMessage(text), PayrollIntent.class).block();
        if (msg == null) {
            throw new PayrollException("模型未返回结构化结果");
        }
        PayrollIntent intent = msg.getStructuredData(PayrollIntent.class);
        if (intent == null) {
            throw new PayrollException("模型未返回可解析的结构化意图");
        }
        System.out.println("结构化意图：");
        System.out.println(JsonSupport.toJson(intent));
    }

    private static void runInteractive(ReActAgent agent) throws Exception {
        System.out.println("=".repeat(72));
        System.out.println("薪酬核算智能体  模型：" + PayrollAgentFactory.modelId());
        System.out.println("示例：算一下张伟 2026-10 的工资 / 研发部 10 月工资表 / 社保基数怎么定");
        System.out.println("输入 exit 退出");
        System.out.println("=".repeat(72));

        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("你：");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("再见。");
                return;
            }
            if (input.isBlank()) {
                continue;
            }
            System.out.print("\n薪酬智能体：");
            agent.streamEvents(new UserMessage(input.trim()))
                    .doOnNext(PayrollApplication::renderEvent)
                    .blockLast();
            System.out.println();
        }
    }

    private static void renderEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
            System.out.flush();
        } else if (event instanceof ToolCallStartEvent tool) {
            System.out.println("\n  [调用工具] " + tool.getToolCallName());
            System.out.print("  ");
        } else if (event instanceof ExceedMaxItersEvent) {
            System.out.println("\n  [提示] 已达到最大工具调用轮次，请缩小问题范围后重试。");
        }
    }

    private static void printBatch(BatchPayrollResult batch) {
        System.out.println();
        System.out.println(
                "期间 "
                        + batch.period()
                        + "  范围 "
                        + batch.department()
                        + "  人数 "
                        + batch.employeeCount());
        System.out.printf(
                "%-8s %-6s %14s %14s %14s %14s%n",
                "工号", "姓名", "应发", "扣款合计", "实发", "企业成本");
        System.out.println("-".repeat(76));
        for (PayrollResult record : batch.records()) {
            System.out.printf(
                    "%-8s %-6s %14s %14s %14s %14s%n",
                    record.employeeId(),
                    record.employeeName(),
                    Money.format(record.grossPay()),
                    Money.format(record.totalDeductions()),
                    Money.format(record.netPay()),
                    Money.format(record.employerCost()));
        }
        System.out.println("-".repeat(76));
        System.out.printf(
                "%-16s%14s%14s%14s%14s%n",
                "合计",
                Money.format(batch.totalGross()),
                Money.format(batch.totalDeductions()),
                Money.format(batch.totalNet()),
                Money.format(batch.totalEmployerCost()));
        if (!batch.failures().isEmpty()) {
            System.out.println("核算失败：");
            batch.failures()
                    .forEach(f -> System.out.println("  - " + f.employeeId() + "：" + f.message()));
        }
        System.out.println();
    }

    private static void printValidation(PayrollValidation validation) {
        System.out.println(
                "发薪前校验：" + (validation.valid() ? "通过" : "存在风险，需复核"));
        for (PayrollValidation.CheckResult check : validation.checks()) {
            System.out.println(
                    "  [" + (check.passed() ? "PASS" : "FAIL") + "] "
                            + check.check() + " — " + check.detail());
        }
        System.out.println();
    }

    private static YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new PayrollException("工资期间格式错误：" + period + "，应为 YYYY-MM");
        }
    }

    private static void printHelp() {
        System.out.println(
                """
                薪酬核算智能体

                用法：
                  --demo                              离线演示（无需 API Key）
                  --payroll EMP001 --period 2026-10   单人核算并打印工资条
                  --department 研发部 --period 2026-10 部门批量核算
                  --extract "帮我算下张伟10月的工资"    结构化意图抽取
                  无参数                               交互式对话

                环境变量：
                  DASHSCOPE_API_KEY    DashScope API Key（在线对话必填）
                  PAYROLL_MODEL        模型标识，默认 dashscope:qwen-plus
                """);
    }

    private static final class Options {
        private boolean demo;
        private boolean help;
        private String period;
        private String payrollId;
        private String department;
        private String extractText;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--demo" -> options.demo = true;
                    case "--help", "-h" -> options.help = true;
                    case "--period" -> options.period = requireValue(args, ++i, "--period");
                    case "--payroll" -> options.payrollId = requireValue(args, ++i, "--payroll");
                    case "--department" ->
                            options.department = requireValue(args, ++i, "--department");
                    case "--extract" -> options.extractText = requireValue(args, ++i, "--extract");
                    default -> throw new PayrollException("未知参数：" + args[i] + "，使用 --help 查看用法");
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new PayrollException("参数 " + option + " 缺少取值");
            }
            return args[index];
        }
    }
}
