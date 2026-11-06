package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 组装薪酬智能体：模型只做理解与编排，工具承担全部计算与数据访问。 */
public final class PayrollAgentFactory {

    public static final String DEFAULT_MODEL = "ollama:qwen2.5:3b-instruct";
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private static final String OLLAMA_PREFIX = "ollama:";
    private static final int DEFAULT_OLLAMA_NUM_CTX = 8192;
    private static final double DEFAULT_OLLAMA_TEMPERATURE = 0.1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            """
            你是「薪酬核算智能体」，是 HR 薪酬专员的核算副驾。

            ## 绝对规则
            1. 所有金额、税额、比例、考勤天数必须来自工具返回结果；禁止心算、估算、四舍五入或改写工具数字。
            2. 工具返回 status=ERROR 时，按 message 修正参数后重试一次，仍失败再向用户说明；禁止用假设数据继续计算。
            3. 工资期间统一使用 YYYY-MM。用户未给出期间时先追问，不得默认当前月份。
            4. 员工参数可传工号或唯一姓名；出现同名、未找到或部门范围不清时，用 list_employees 确认后再试。
            5. 不得虚构员工、考勤、绩效或政策参数；政策口径以 get_payroll_policy 为准。
            6. 银行卡号只展示脱敏值，禁止展开、补全或推断完整卡号。
            7. 给出任何发薪结论时注明“以系统核算结果为准，发薪前需 HR 复核”。
            8. 字段语义：gross_pay 为应发合计；total_deductions 为全部扣款合计（考勤扣款 + 个人社保 + 个人公积金 + 个税）；net_pay 为实发；overtime_pay 属于应发项目，禁止列为扣款。
            9. 明细中的“扣款项目”合计必须等于 total_deductions；社保公积金只能引用 social_insurance_employee 与 housing_fund_employee，禁止把 total_deductions 当作社保公积金金额。

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
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        return ReActAgent.builder()
                .name("PayrollAgent")
                .description("企业薪酬核算智能体，负责理解 HR 诉求、调用确定性引擎并解释结果")
                .sysPrompt(SYSTEM_PROMPT)
                .model(buildModel(modelId()))
                .toolkit(toolkit)
                .maxIters(12)
                .build();
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

        OllamaOptions options =
                OllamaOptions.builder()
                        .numCtx(intEnv("PAYROLL_OLLAMA_NUM_CTX", DEFAULT_OLLAMA_NUM_CTX))
                        .temperature(
                                doubleEnv(
                                        "PAYROLL_OLLAMA_TEMPERATURE",
                                        DEFAULT_OLLAMA_TEMPERATURE))
                        .build();

        return OllamaChatModel.builder()
                .modelName(modelName)
                .baseUrl(baseUrl)
                .defaultOptions(options)
                .build();
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
