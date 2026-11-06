package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.model.OllamaThinkingModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组装薪酬多智能体：计算、验证、核算三个角色共享同一模型，但使用不同的系统提示词与工具集。
 *
 * <p>流水线顺序由 {@code PayrollChatService} 固定编排，模型只负责理解、调用工具与解释结果；
 * 金额计算始终由 {@link io.agentscope.examples.payroll.service.PayrollEngine} 完成。
 */
public final class PayrollAgentFactory {

    public static final String DEFAULT_MODEL = "ollama:qwen2.5:3b-instruct";
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private static final Logger log = LoggerFactory.getLogger(PayrollAgentFactory.class);
    private static final String OLLAMA_PREFIX = "ollama:";
    private static final int DEFAULT_OLLAMA_NUM_CTX = 8192;
    private static final double DEFAULT_OLLAMA_TEMPERATURE = 0.1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> CALCULATOR_TOOLS =
            Set.of(
                    "list_employees",
                    "get_employee_profile",
                    "get_attendance_summary",
                    "calculate_payroll",
                    "calculate_department_payroll",
                    "generate_payslip",
                    "get_payroll_policy");

    private static final Set<String> VALIDATOR_TOOLS =
            Set.of(
                    "validate_payroll",
                    "get_payroll_policy",
                    "get_employee_profile",
                    "list_employees",
                    "get_attendance_summary");

    private static final String COMMON_RULES =
            """
            ## 绝对规则
            1. 所有金额、税额、比例、考勤天数必须来自工具返回结果；禁止心算、估算、四舍五入或改写工具数字。
            2. 工具返回 status=ERROR 时，按 message 修正参数后重试一次，仍失败再说明；禁止用假设数据继续。
            3. 工资期间统一使用 YYYY-MM。用户未给出期间时先追问，不得默认当前月份。
            4. 员工参数可传工号或唯一姓名；出现同名、未找到或部门范围不清时，用 list_employees 确认后再试。
            5. 不得虚构员工、考勤、绩效或政策参数；政策口径以 get_payroll_policy 为准。
            6. 银行卡号只展示脱敏值，禁止展开、补全或推断完整卡号。
            7. 字段语义：gross_pay 为应发合计；total_deductions 为全部扣款合计（考勤扣款 + 个人社保 + 个人公积金 + 个税）；net_pay 为实发；overtime_pay 属于应发项目，禁止列为扣款。
            8. 明细中的“扣款项目”合计必须等于 total_deductions；社保公积金只能引用 social_insurance_employee 与 housing_fund_employee，禁止把 total_deductions 当作社保公积金金额。
            """;

    private static final String CALCULATOR_PROMPT =
            COMMON_RULES
                    + """
                    
                    ## 角色
                    你是「薪酬计算智能体」，多智能体流水线的第 1 环。只负责理解诉求、调用确定性工具完成计算，
                    并把结果整理成供下游“验证智能体”复核的计算记录。你的输出不是最终用户答复。

                    ## 工具选择
                    - 单人核算 → calculate_payroll
                    - 工资条文本 → generate_payslip
                    - 部门/全员批量 → calculate_department_payroll
                    - 考勤核对 → get_attendance_summary
                    - 政策、税率、比例 → get_payroll_policy
                    - 员工定位 → list_employees / get_employee_profile

                    ## 输出格式（纯文本，不要 JSON 代码块）
                    1. 核算对象：员工/部门 + 期间 + 诉求类型
                    2. 计算结果：逐项列出工具返回的应发、扣款合计、实发、企业成本、个税、社保公积金、考勤等关键值
                    3. 风险提示：引用工具 warnings；没有写“无”
                    4. 待验证点：列出需要复核的字段、异常或缺失信息

                    非工资计算类问题（政策、考勤查询）同样输出“查询结果 + 风险/待验证点”，不要下最终发薪结论。
                    """;

    private static final String VALIDATOR_PROMPT =
            COMMON_RULES
                    + """
                    
                    ## 角色
                    你是「薪酬验证智能体」，多智能体流水线的第 2 环。只负责独立复核计算智能体的记录，
                    不面向用户输出最终答复，也不得重新编写工资结论。

                    ## 必须执行的校验
                    1. 对“核算对象”列表中的每个对象调用 validate_payroll，员工与期间必须与列表一致。
                    2. 必要时调用 get_payroll_policy、get_employee_profile、get_attendance_summary 核对政策与原始数据。
                    3. 若计算记录与工具校验结果冲突，以工具结果为准，并明确指出冲突字段与两边的值。
                    4. 批量场景最多抽检 3 名员工，其余按汇总结果做勾稽检查。

                    ## 校验重点
                    - gross_pay - total_deductions = net_pay
                    - 扣款分项合计 = total_deductions
                    - 社保、公积金个人部分与基数/比例一致
                    - 个税累计预扣与税率表一致
                    - 实发不为负，社保基数在上下限内
                    - 考勤扣款、加班费、入职首月折算是否合理

                    ## 输出格式（纯文本）
                    结论：通过 / 有风险 / 不通过
                    校验明细：每个对象一行，列出 PASS/FAIL 项与关键数值
                    发现问题：逐条列出对象、字段、工具值与期望关系；没有写“无”
                    复核建议：需要 HR 人工确认的事项

                    若没有核算对象，输出“本轮无需验证”并说明原因。
                    """;

    private static final String ACCOUNTANT_PROMPT =
            COMMON_RULES
                    + """
                    
                    ## 角色
                    你是「薪酬核算智能体」，多智能体流水线的第 3 环，负责把计算记录与验证结论汇总成给 HR 的最终答复。

                    ## 汇总规则
                    1. 只基于【计算智能体记录】和【验证智能体记录】作答，不得新增、改动或心算任何金额。
                    2. 验证结论为“不通过”时，必须明确写“暂不可发薪，需 HR 复核”，禁止给出相反结论。
                    3. 验证结论为“有风险”时，必须在结论与风险提示中显著标出。
                    4. 验证记录缺失或显示执行失败时，必须注明“本轮未完成独立验证”，不得声称已通过校验。
                    5. 不输出工具 JSON 原文，不解释多智能体内部实现，除非用户主动询问过程。
                    6. 两个记录缺少用户问题所需的信息时，明确说明缺少什么，不要猜测。

                    ## 回答结构
                    单人核算：结论（实发 X 元；应发 Y 元；扣款 Z 元；验证结论）→ 明细（应发/扣款/实发）→ 个税 → 验证与风险 → 复核提示
                    批量核算：人数、应发/实发/企业成本合计 → 异常人员 → 验证结论 → 复核提示
                    政策/考勤咨询：直接给结论与依据 → 风险/注意事项

                    金额保留两位小数并带千分位；结尾固定注明“以系统核算结果为准，发薪前需 HR 复核”。
                    """;

    private static final String INTENT_PROMPT =
            """
            你是薪酬意图抽取器。把用户输入解析为结构化意图，只输出字段值，不要解释。
            action 取值：calculate / batch / payslip / attendance / policy / validate / unknown。
            工资期间使用 YYYY-MM；未提及则留空。员工可填工号或姓名，无法确定时留空。
            """;

    private PayrollAgentFactory() {
    }

    /** 三个智能体共享模型实例，避免重复探测 Ollama 与重复加载配置。 */
    public record Team(ReActAgent calculator, ReActAgent validator, ReActAgent accountant)
            implements AutoCloseable {

        @Override
        public void close() {
            calculator.close();
            validator.close();
            accountant.close();
        }
    }

    public static Team createTeam(PayrollTools tools) {
        Model model = buildModel(modelId());
        log.info("[多智能体] 初始化完成：计算 / 验证 / 核算三个智能体，模型={}", modelId());
        return new Team(
                createAgent(
                        "PayrollCalculatorAgent",
                        "薪酬计算智能体：理解诉求并调用确定性工具完成核算，产出可复核的计算记录",
                        CALCULATOR_PROMPT,
                        model,
                        toolkit(tools, CALCULATOR_TOOLS, "计算"),
                        12),
                createAgent(
                        "PayrollValidatorAgent",
                        "薪酬验证智能体：独立调用发薪前校验工具，复核勾稽关系与风险项",
                        VALIDATOR_PROMPT,
                        model,
                        toolkit(tools, VALIDATOR_TOOLS, "验证"),
                        8),
                createAgent(
                        "PayrollAccountantAgent",
                        "薪酬核算智能体：汇总计算与验证记录，生成最终可审计答复",
                        ACCOUNTANT_PROMPT,
                        model,
                        new Toolkit(),
                        4));
    }

    /** CLI 的 --extract 模式仍使用单智能体做结构化意图抽取。 */
    public static ReActAgent createIntentAgent(PayrollTools tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return createAgent(
                "PayrollIntentAgent",
                "薪酬意图抽取智能体：把自然语言解析为结构化意图",
                INTENT_PROMPT,
                buildModel(modelId()),
                toolkit,
                6);
    }

    private static ReActAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters) {
        return ReActAgent.builder()
                .name(name)
                .description(description)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    private static Toolkit toolkit(PayrollTools tools, Set<String> allowed, String role) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        for (String name : Set.copyOf(toolkit.getToolNames())) {
            if (!allowed.contains(name)) {
                toolkit.removeTool(name);
            }
        }
        log.info("[多智能体] {}智能体工具集：{}", role, toolkit.getToolNames());
        return toolkit;
    }

    public static String modelId() {
        String configured = System.getenv("PAYROLL_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    public static String ollamaBaseUrl() {
        return env("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
    }

    private static Model buildModel(String modelId) {
        if (modelId.startsWith(OLLAMA_PREFIX)) {
            return buildOllamaModel(modelId.substring(OLLAMA_PREFIX.length()).trim());
        }
        if (modelId.startsWith("dashscope:")) {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new PayrollException(
                        "未设置 DASHSCOPE_API_KEY，无法调用 DashScope 模型。"
                                + "可改用默认的本地 Ollama 模型，或先运行 --demo 验证核算引擎。");
            }
        }
        return ModelRegistry.resolve(modelId);
    }

    private static Model buildOllamaModel(String modelName) {
        if (modelName.isBlank()) {
            throw new PayrollException(
                    "PAYROLL_MODEL 格式错误，应为 ollama:<模型名>，例如 ollama:qwen2.5:3b-instruct");
        }
        String baseUrl = ollamaBaseUrl();
        verifyOllamaModel(baseUrl, modelName);
        Set<String> capabilities = ollamaCapabilities(baseUrl, modelName);
        boolean supportsThinking = capabilities.contains("thinking");
        log.info(
                "[多智能体] Ollama 模型={}，地址={}，能力={}，思考输出={}",
                modelName,
                baseUrl,
                capabilities,
                supportsThinking);

        OllamaOptions.Builder builder =
                OllamaOptions.builder()
                        .numCtx(intEnv("PAYROLL_OLLAMA_NUM_CTX", DEFAULT_OLLAMA_NUM_CTX))
                        .temperature(
                                doubleEnv(
                                        "PAYROLL_OLLAMA_TEMPERATURE",
                                        DEFAULT_OLLAMA_TEMPERATURE));
        ThinkOption thinkOption = ollamaThinkOption(modelName, supportsThinking);
        if (thinkOption != null) {
            builder.thinkOption(thinkOption);
        }
        OllamaOptions options = builder.build();

        if (thinkOption != null && !isDisabled(thinkOption)) {
            return new OllamaThinkingModel(modelName, baseUrl, options);
        }
        return OllamaChatModel.builder()
                .modelName(modelName)
                .baseUrl(baseUrl)
                .defaultOptions(options)
                .build();
    }

    private static boolean isDisabled(ThinkOption thinkOption) {
        return thinkOption instanceof ThinkOption.ThinkBoolean thinkBoolean
                && !thinkBoolean.enabled();
    }

    /**
     * 思考开关：未设置时按模型能力自动决定，避免对 qwen2.5-instruct 等模型发送 think 参数。
     *
     * <p>PAYROLL_OLLAMA_THINK 可取 true/false/low/medium/high；显式开启时要求模型具备
     * thinking 能力（如 deepseek-r1、qwen3）。
     */
    private static ThinkOption ollamaThinkOption(String modelName, boolean supportsThinking) {
        String value = env("PAYROLL_OLLAMA_THINK", "");
        if (value.isEmpty()) {
            return supportsThinking ? ThinkOption.ThinkBoolean.ENABLED : null;
        }
        ThinkOption option =
                switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "1", "on", "yes" -> ThinkOption.ThinkBoolean.ENABLED;
                    case "false", "0", "off", "no" -> ThinkOption.ThinkBoolean.DISABLED;
                    case "low" -> ThinkOption.ThinkLevel.LOW;
                    case "medium" -> ThinkOption.ThinkLevel.MEDIUM;
                    case "high" -> ThinkOption.ThinkLevel.HIGH;
                    default ->
                            throw new PayrollException(
                                    "PAYROLL_OLLAMA_THINK 只能是 true/false/low/medium/high，当前值："
                                            + value);
                };
        if (!isDisabled(option) && !supportsThinking) {
            throw new PayrollException(
                    "模型 "
                            + modelName
                            + " 不支持思考输出，请改用 deepseek-r1、qwen3 等模型，"
                            + "或设置 PAYROLL_OLLAMA_THINK=false。");
        }
        return option;
    }

    /** 尽早暴露“服务未启动 / 模型未拉取”，避免用户拿到难以理解的连接异常。 */
    private static void verifyOllamaModel(String baseUrl, String modelName) {
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags");
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PayrollException(
                    "无法连接 Ollama（" + baseUrl + "）。请先启动服务：ollama serve；"
                            + "如不在默认地址，请设置 OLLAMA_BASE_URL。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PayrollException("连接 Ollama 被中断，请重试。");
        }

        if (response.statusCode() != 200) {
            throw new PayrollException(
                    "Ollama 返回 HTTP " + response.statusCode() + "，请检查服务状态：" + baseUrl);
        }
        try {
            for (JsonNode model : JSON.readTree(response.body()).path("models")) {
                if (modelName.equals(model.path("name").asText())
                        || modelName.equals(model.path("model").asText())) {
                    return;
                }
            }
        } catch (IOException e) {
            throw new PayrollException("无法解析 Ollama 模型列表，请确认 Ollama 版本兼容。");
        }
        throw new PayrollException(
                "Ollama 未安装模型 " + modelName + "。请先执行：ollama pull " + modelName);
    }

    /**
     * 读取模型能力；旧版 Ollama 不支持 /api/show 时返回空集合，退化为不启用思考。
     */
    private static Set<String> ollamaCapabilities(String baseUrl, String modelName) {
        Set<String> capabilities = new HashSet<>();
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/show");
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String body;
        try {
            body = JSON.writeValueAsString(java.util.Map.of("model", modelName));
        } catch (IOException e) {
            return capabilities;
        }
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ollama /api/show 返回 HTTP {}，不启用思考输出。", response.statusCode());
                return capabilities;
            }
            for (JsonNode item : JSON.readTree(response.body()).path("capabilities")) {
                capabilities.add(item.asText());
            }
        } catch (IOException e) {
            log.warn("无法读取 Ollama 模型能力（{}），不启用思考输出。", modelName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return capabilities;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new PayrollException(key + " 必须是整数，当前值：" + value);
        }
    }

    private static double doubleEnv(String key, double defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new PayrollException(key + " 必须是数字，当前值：" + value);
        }
    }
}
