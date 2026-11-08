package io.agentscope.examples.payroll;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollIntent;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.Money;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollChatService;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.service.PayslipFormatter;
import io.agentscope.examples.payroll.support.JsonSupport;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;

/**
 * 薪酬智能体命令行入口。
 *
 * <pre>
 *   --web                           启动本地 Web 管理台（员工/考核/政策/工资批次）
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
            if (options.web) {
                runWeb(options);
                return;
            }

            PayrollServices services = PayrollServices.open();
            PayrollEngine engine = services.engine();
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

            if (options.extractText != null) {
                try (HarnessAgent agent = PayrollAgentFactory.createIntentAgent(tools)) {
                    runExtraction(agent, options.extractText);
                }
            } else {
                try (PayrollChatService chat = new PayrollChatService(engine)) {
                    runInteractive(chat);
                }
            }
        } catch (PayrollException e) {
            System.err.println("错误：" + e.getMessage());
            System.exit(1);
        }
    }

    /** 以 Spring Boot 内嵌 Tomcat 启动管理台；CLI 其他模式不启动 Spring 容器。 */
    private static void runWeb(Options options) {
        List<String> args = new ArrayList<>();
        args.add("--server.address=127.0.0.1");
        if (options.port != null) {
            args.add("--server.port=" + parsePort(options.port));
        }
        SpringApplication.run(PayrollWebApplication.class, args.toArray(String[]::new));
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new PayrollException("端口不合法：" + value);
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

    private static void runExtraction(HarnessAgent agent, String text) {
        Msg msg = agent.call(List.of(new UserMessage(text)), PayrollIntent.class).block();
        if (msg == null) {
            throw new PayrollException("模型未返回结构化结果");
        }
        PayrollIntent intent;
        try {
            intent = msg.getStructuredData(PayrollIntent.class);
        } catch (RuntimeException e) {
            intent = null;
        }
        if (intent == null) {
            throw new PayrollException(
                    "当前模型未返回结构化意图。本地 Ollama 小模型的工具调用稳定性有限，"
                            + "请改用交互式对话，或设置 PAYROLL_MODEL=dashscope:qwen-plus 后重试。");
        }
        System.out.println("结构化意图：");
        System.out.println(JsonSupport.toJson(intent));
    }

    private static void runInteractive(PayrollChat chat) throws Exception {
        String modelId = PayrollAgentFactory.modelId();
        System.out.println("=".repeat(72));
        System.out.println(
                "薪酬多智能体  计算 → 验证 → 核算  模型："
                        + modelId
                        + (modelId.startsWith("ollama:")
                                ? "  Ollama：" + PayrollAgentFactory.ollamaBaseUrl()
                                : ""));
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
            try {
                chat.stream(input.trim(), new ConsoleChatListener());
            } catch (PayrollException e) {
                System.out.println("\n错误：" + e.getMessage());
            }
            System.out.println();
        }
    }

    /** CLI 复用与管理台相同的三段流水线，只把事件渲染成终端文本。 */
    private static final class ConsoleChatListener implements ChatStreamListener {

        @Override
        public void onAgentStart(String agent, String label) {
            System.out.println("\n\n[" + label + "]");
        }

        @Override
        public void onAgentOutput(String agent, String delta) {
            System.out.print(delta);
            System.out.flush();
        }

        @Override
        public void onToolStart(String toolCallId, String toolName) {
            System.out.println("\n  [工具] " + toolName);
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, String state) {
            System.out.println("  [状态] " + toolName + " → " + state);
        }

        @Override
        public void onPhase(String label) {
            System.out.println("\n  [阶段] " + label);
        }

        @Override
        public void onText(String delta) {
            System.out.print(delta);
            System.out.flush();
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
                  --web [--port 8080]                 启动本地 Web 管理台
                  --demo                              离线演示（无需模型）
                  --payroll EMP001 --period 2026-10   单人核算并打印工资条
                  --department 研发部 --period 2026-10 部门批量核算
                  --extract "帮我算下张伟10月的工资"    结构化意图抽取（建议使用 DashScope 模型）
                  无参数                               交互式对话

                管理台：
                  http://127.0.0.1:8080  维护员工/考核/城市政策/考勤，
                  预览并微调工资批次，出账生成 Excel 并入库，确认后 dry-run 发送全员邮件。

                对话流程（多智能体）：
                  ① 计算智能体  理解诉求，调用 calculate_payroll 等工具完成核算
                  ② 验证智能体  对核算对象调用 validate_payroll，复核勾稽关系与风险
                  ③ 核算智能体  汇总计算与验证记录，生成最终答复
                  三阶段按固定顺序执行；验证失败时降级为“本轮未完成独立验证”。

                日志：
                  控制台输出每个阶段的开始/结束、耗时、工具调用参数与结果；
                  日志写入 stderr，与 CLI 核算结果分开，便于开发排查。

                数据与输出：
                  data/payroll.mv.db     H2 文件库（首次启动自动导入演示数据）
                  output/*.xlsx          工资批次 Excel
                  PAYROLL_DB_URL         自定义 JDBC URL
                  PAYROLL_DB_USER        数据库用户，默认 sa
                  PAYROLL_DB_PASSWORD    数据库密码，默认空
                  PAYROLL_OUTPUT_DIR     Excel 输出目录，默认 output
                  PAYROLL_WEB_PORT       管理台端口，默认 8080

                本地模型（默认）：
                  1. ollama serve
                  2. ollama pull qwen2.5:3b-instruct
                  3. java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar

                环境变量：
                  PAYROLL_MODEL              模型标识，默认 ollama:qwen2.5:3b-instruct
                                             DashScope 可设为 dashscope:qwen-plus
                  OLLAMA_BASE_URL            Ollama 地址，默认 http://localhost:11434
                  PAYROLL_OLLAMA_NUM_CTX     Ollama 上下文窗口，默认 8192
                  PAYROLL_OLLAMA_TEMPERATURE Ollama 温度，默认 0.1
                  DASHSCOPE_API_KEY          仅 DashScope 模型必填
                """);
    }

    private static final class Options {
        private boolean demo;
        private boolean web;
        private boolean help;
        private String period;
        private String payrollId;
        private String department;
        private String extractText;
        private String port;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--demo" -> options.demo = true;
                    case "--web" -> options.web = true;
                    case "--port" -> options.port = requireValue(args, ++i, "--port");
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
