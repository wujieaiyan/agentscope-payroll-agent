package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.model.OllamaThinkingModel;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int DEFAULT_OLLAMA_NUM_PREDICT = 1024;
    private static final double DEFAULT_OLLAMA_TEMPERATURE = 0.1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AgentCache AGENTS = new AgentCache();
    private static volatile Model sharedModel;
    private static volatile String sharedModelId;

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

    private static final String SKILL_BOOT_RULE =
            """
            ## 角色技能（强制第一步）
            开始任何回答前，必须先调用 load_skill_through_path(skillId="%s", path="SKILL.md")，
            读取后再严格遵循其中的角色说明、工具选择与输出格式。
            技能加载成功前，禁止调用任何业务工具，也禁止直接回答用户。
            只能加载该 skillId；本智能体没有任何其他可用技能，禁止改用其他技能重试。
            若加载失败，直接说明“角色技能未加载”，并按下方绝对规则作答。
            禁止在正文中输出工具调用名称、JSON 参数或“请加载技能”之类的指令。
            """;

    /** 预加载技能正文的展示上限，避免超长技能挤占上下文。 */
    private static final int MAX_SKILL_CONTENT_CHARS = 8_000;

    private static final int MAX_SKILL_BLOCK_CHARS = 24_000;

    /** 自定义技能智能体的通用规则：不复用薪酬口径，避免把非薪酬角色拉回核算场景。 */
    private static final String GENERAL_RULES =
            """
            ## 绝对规则
            1. 所有事实、数据和工具结果必须来自已加载技能或工具返回；禁止编造、臆测或改写。
            2. 需要外部数据或执行操作时调用可用工具，核对返回结果后再作答；工具报错时按提示修正参数重试一次。
            3. 信息不足时明确追问，不得用假设内容继续。
            4. 不得虚构或泄露用户隐私数据。
            """;

    private static String calculatorPrompt(String skillId) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + """
                ## 基本职责
                你是「薪酬计算智能体」，多智能体流水线的第 1 环。只负责理解诉求、调用确定性工具完成计算，
                并整理出供下游“验证智能体”复核的计算记录；你的输出不是最终用户答复。
                """;
    }

    private static String validatorPrompt(String skillId) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + """
                ## 基本职责
                你是「薪酬验证智能体」，多智能体流水线的第 2 环。只负责独立复核计算记录，不面向用户输出最终答复。
                技能未加载时，至少对每个核算对象调用 validate_payroll，并核对 gross_pay - total_deductions = net_pay。
                """;
    }

    private static String accountantPrompt(String skillId) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + """
                ## 基本职责
                你是「薪酬核算智能体」，多智能体流水线的第 3 环，负责把计算记录与验证结论汇总成给 HR 的最终答复。
                技能未加载时，只转述两份记录并标注“角色技能未加载”，不得新增或心算任何金额。
                """;
    }

    private static final String SUMMARIZER_PROMPT =
            """
            你是薪酬会话的上下文压缩器。把【已有摘要】与【待压缩对话】合并成一段新的中文摘要，
            供后续轮次理解“他”“上个月”“同一部门”这类指代。

            ## 必须保留
            - 用户关注的员工姓名或工号、部门范围、工资期间
            - 追问链与指代关系（例如“他”指哪位员工，“上个月”是哪个月）
            - 尚未完成的事项、用户明确要求的输出形式或限制

            ## 禁止保留
            - 任何具体金额、税额、社保公积金数额、考勤天数、政策比例或工具原始数据
            - 这些数据会随管理台维护而变化，后续必须重新调用工具查询

            不要推测、评价或寒暄。输出纯文本，不超过 400 字，不使用 Markdown；
            没有可保留的信息时只输出“无”。
            """;

    private static final String INTENT_PROMPT =
            """
            你是薪酬意图抽取器。把用户输入解析为结构化意图，只输出字段值，不要解释。
            action 取值：calculate / batch / payslip / attendance / policy / validate / unknown。
            工资期间使用 YYYY-MM；未提及则留空。员工可填工号或姓名，无法确定时留空。
            """;

    private PayrollAgentFactory() {
    }

    /** 三个业务智能体与上下文压缩器共享模型实例，避免重复探测 Ollama 与重复加载配置。 */
    public record Team(
            HarnessAgent calculator,
            HarnessAgent validator,
            HarnessAgent accountant,
            HarnessAgent summarizer,
            List<McpClientWrapper> mcpClients,
            AgentDefinition activeAgent,
            boolean pipeline)
            implements AutoCloseable {

        public Team {
            mcpClients = mcpClients == null ? List.of() : List.copyOf(mcpClients);
        }

        /** 兼容仅按默认流水线组装的调用方（测试/CLI）。 */
        public Team(
                HarnessAgent calculator,
                HarnessAgent validator,
                HarnessAgent accountant,
                HarnessAgent summarizer,
                List<McpClientWrapper> mcpClients) {
            this(calculator, validator, accountant, summarizer, mcpClients, null, true);
        }

        /** 清空本会话在各智能体上的历史；共享实例不能按会话关闭。 */
        public void clearSession(RuntimeContext context) {
            clearAgent(calculator, context);
            if (validator != calculator) {
                clearAgent(validator, context);
            }
            if (accountant != calculator) {
                clearAgent(accountant, context);
            }
            if (summarizer != calculator) {
                clearAgent(summarizer, context);
            }
        }

        private static void clearAgent(HarnessAgent agent, RuntimeContext context) {
            if (agent == null) {
                return;
            }
            try {
                agent.clearContext(context);
            } catch (RuntimeException e) {
                log.warn("[多智能体] 清理会话上下文失败", e);
            }
        }

        @Override
        public void close() {
            closeAgent(calculator);
            if (validator != calculator) {
                closeAgent(validator);
            }
            if (accountant != calculator) {
                closeAgent(accountant);
            }
            closeAgent(summarizer);
            for (McpClientWrapper client : mcpClients) {
                try {
                    client.close();
                } catch (RuntimeException e) {
                    log.warn("[MCP] 关闭客户端失败：{}", client.getName(), e);
                }
            }
        }

        private static void closeAgent(HarnessAgent agent) {
            if (agent == null) {
                return;
            }
            try {
                agent.close();
            } catch (RuntimeException e) {
                log.warn("[多智能体] 关闭智能体失败", e);
            }
        }
    }

    /** 流程节点运行时：独立智能体 + 该节点注册的 MCP 客户端，结束即释放。 */
    public record NodeAgent(HarnessAgent agent, List<McpClientWrapper> mcpClients)
            implements AutoCloseable {

        public NodeAgent {
            mcpClients = mcpClients == null ? List.of() : List.copyOf(mcpClients);
        }

        @Override
        public void close() {
            try {
                agent.close();
            } catch (RuntimeException e) {
                log.warn("[流程编排] 关闭节点智能体失败", e);
            }
            for (McpClientWrapper client : mcpClients) {
                try {
                    client.close();
                } catch (RuntimeException e) {
                    log.warn("[MCP] 关闭节点客户端失败：{}", client.getName(), e);
                }
            }
        }
    }

    /** 绑定薪酬计算技能的智能体走「计算 → 验证 → 核算」流水线，其余智能体按技能单兵作答。 */
    public static boolean isPipeline(AgentDefinition activeAgent) {
        return activeAgent == null || activeAgent.skillIds().contains(RoleSkills.CALCULATOR);
    }

    public static Team createTeam(PayrollTools tools) {
        return createTeam(tools, null);
    }

    /**
     * 进程级复用智能体定义：同一模式 + 定义签名 + 模型只构建一次。
     *
     * <p>每个缓存实例只注册自己绑定的技能（{@code SkillRepositories.scoped}），因此共享
     * 智能体之间不存在跨角色加载技能的问题；会话历史由传入的 {@link RuntimeContext} 隔离。
     */
    public static Team sharedTeam(PayrollEngine engine, AgentDefinition activeAgent) {
        String key =
                "team|"
                        + runtimeKey(engine)
                        + "|"
                        + (isPipeline(activeAgent) ? "pipeline" : definitionSignature(activeAgent))
                        + "|"
                        + modelId();
        return AGENTS.get(key, () -> createTeam(new PayrollTools(engine), activeAgent));
    }

    /** 流程节点按智能体定义复用；节点级会话隔离由调用方的 RuntimeContext 保证。 */
    public static NodeAgent sharedFlowNodeAgent(PayrollEngine engine, AgentDefinition agent) {
        String key = "node|" + runtimeKey(engine) + "|" + definitionSignature(agent) + "|" + modelId();
        return AGENTS.get(
                key, () -> createFlowNodeAgent(new PayrollTools(engine), agent, sharedModel()));
    }

    /**
     * 生产环境只有一个 {@link PayrollEngine}；把引擎身份纳入 key，避免测试或嵌入场景
     * 复用另一个引擎上的工具实例。
     */
    private static String runtimeKey(PayrollEngine engine) {
        return engine == null ? "none" : "engine@" + System.identityHashCode(engine);
    }

    /** 定义签名：任一字段变化都会生成新的缓存条目。 */
    static String definitionSignature(AgentDefinition agent) {
        if (agent == null) {
            return "";
        }
        return agent.id()
                + "|"
                + agent.name()
                + "|"
                + agent.description()
                + "|"
                + agent.skillIds()
                + "|"
                + skillFingerprint(agent.skillIds());
    }

    /** 技能正文参与缓存签名：Nacos 技能改动后，旧的系统提示词必须失效。 */
    private static String skillFingerprint(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return "";
        }
        AgentSkillRepository repository = SkillRepositories.shared();
        StringBuilder fingerprint = new StringBuilder();
        for (String skillId : new LinkedHashSet<>(skillIds)) {
            AgentSkill skill = repository.getSkill(skillId);
            String content = skill == null || skill.getSkillContent() == null
                    ? "missing"
                    : Integer.toHexString(skill.getSkillContent().hashCode());
            fingerprint.append(skillId).append(':').append(content).append(';');
        }
        return fingerprint.toString();
    }

    public static Team createTeam(PayrollTools tools, AgentDefinition activeAgent) {
        Model model = sharedModel();
        AgentSkillRepository skillRepository = SkillRepositories.shared();
        HarnessAgent summarizer =
                createAgent(
                        "PayrollContextAgent",
                        "薪酬会话上下文压缩器：把较早轮次折叠成不含金额的中文摘要",
                        SUMMARIZER_PROMPT,
                        model,
                        new Toolkit(),
                        1);
        if (!isPipeline(activeAgent)) {
            return createActiveAgentTeam(tools, activeAgent, model, skillRepository, summarizer);
        }
        AgentSkill calculatorSkill =
                SkillRepositories.activeSkill(skillRepository, RoleSkills.CALCULATOR);
        AgentSkill validatorSkill =
                SkillRepositories.activeSkill(skillRepository, RoleSkills.VALIDATOR);
        AgentSkill accountantSkill =
                SkillRepositories.activeSkill(skillRepository, RoleSkills.ACCOUNTANT);
        Toolkit calculatorTools = toolkit(tools, CALCULATOR_TOOLS, "计算", calculatorSkill);
        List<McpClientWrapper> mcpClients =
                McpToolRegistrar.registerAll(
                        calculatorTools,
                        McpServerConfig.fromEnvironment(),
                        McpToolRegistrar.defaultFactory());
        if (!mcpClients.isEmpty()) {
            log.info("[MCP] 计算智能体最终工具集：{}", calculatorTools.getToolNames());
        }
        log.info(
                "[Skill] 生效技能：{}",
                RoleSkills.ids().stream()
                        .map(id -> SkillRepositories.describe(skillRepository, id))
                        .toList());
        log.info("[多智能体] 初始化完成：计算 / 验证 / 核算三个智能体 + 上下文压缩器，模型={}", modelId());
        return new Team(
                createAgent(
                        "PayrollCalculatorAgent",
                        "薪酬计算智能体：理解诉求并调用确定性工具完成核算，产出可复核的计算记录",
                        calculatorPrompt(calculatorSkill.getSkillId()),
                        model,
                        calculatorTools,
                        12,
                        skillRepository,
                        RoleSkills.CALCULATOR),
                createAgent(
                        "PayrollValidatorAgent",
                        "薪酬验证智能体：独立调用发薪前校验工具，复核勾稽关系与风险项",
                        validatorPrompt(validatorSkill.getSkillId()),
                        model,
                        toolkit(tools, VALIDATOR_TOOLS, "验证", validatorSkill),
                        8,
                        skillRepository,
                        RoleSkills.VALIDATOR),
                createAgent(
                        "PayrollAccountantAgent",
                        "薪酬核算智能体：汇总计算与验证记录，生成最终可审计答复",
                        accountantPrompt(accountantSkill.getSkillId()),
                        model,
                        new Toolkit(),
                        4,
                        skillRepository,
                        RoleSkills.ACCOUNTANT),
                summarizer,
                mcpClients,
                activeAgent,
                true);
    }

    /** 流程编排按节点创建独立智能体；模型实例由调用方按轮次复用，避免重复探测 Ollama。 */
    public static NodeAgent createFlowNodeAgent(
            PayrollTools tools, AgentDefinition agent, Model model) {
        AgentSkillRepository skillRepository = SkillRepositories.shared();
        List<AgentSkill> skills = boundSkills(skillRepository, agent);
        AgentSkill primary = skills.get(0);
        boolean payrollRole = isPayrollRole(agent.skillIds());
        Set<String> allowed = flowTools(agent.skillIds());
        Toolkit toolkit = toolkit(tools, allowed, agent.name(), primary);
        List<McpClientWrapper> mcpClients =
                McpToolRegistrar.registerAll(
                        toolkit,
                        McpServerConfig.fromEnvironment(),
                        McpToolRegistrar.defaultFactory());
        HarnessAgent nodeAgent =
                createAgent(
                        "FlowNode_" + agent.id(),
                        agent.name() + "：" + (agent.description() == null ? "" : agent.description()),
                        flowNodePrompt(agent, skills),
                        model,
                        toolkit,
                        12,
                        // 自定义角色：技能正文已注入提示词，不再注册动态加载工具。
                        payrollRole ? skillRepository : null,
                        payrollRole ? agent.skillIds() : List.of());
        return new NodeAgent(nodeAgent, mcpClients);
    }

    /** 管理员指定的非计算类智能体：技能决定可用工具，单智能体直接面向 HR 作答。 */
    private static Team createActiveAgentTeam(
            PayrollTools tools,
            AgentDefinition activeAgent,
            Model model,
            AgentSkillRepository skillRepository,
            HarnessAgent summarizer) {
        List<AgentSkill> boundSkills = boundSkills(skillRepository, activeAgent);
        AgentSkill primary = boundSkills.get(0);
        Set<String> allowed = allowedTools(activeAgent.skillIds());
        Toolkit toolkit = toolkit(tools, allowed, activeAgent.name(), primary);
        List<McpClientWrapper> mcpClients =
                McpToolRegistrar.registerAll(
                        toolkit,
                        McpServerConfig.fromEnvironment(),
                        McpToolRegistrar.defaultFactory());
        log.info(
                "[Agent] 核算智能体：{}（{}），技能={}，工具={}",
                activeAgent.name(),
                activeAgent.id(),
                activeAgent.skillIds(),
                toolkit.getToolNames());
        HarnessAgent agent =
                createAgent(
                        "PayrollActiveAgent",
                        activeAgent.name() + "：" + activeAgent.description(),
                        activeAgentPrompt(activeAgent, boundSkills),
                        model,
                        toolkit,
                        12,
                        // 技能正文已预加载，移除 load_skill_through_path，避免小模型编造技能 id。
                        null,
                        List.of());
        return new Team(agent, null, null, summarizer, mcpClients, activeAgent, false);
    }

    static List<AgentSkill> boundSkills(
            AgentSkillRepository repository, AgentDefinition activeAgent) {
        List<AgentSkill> skills = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String skillId : activeAgent.skillIds()) {
            if (!seen.add(skillId)) {
                continue;
            }
            if (repository.getSkill(skillId) != null || RoleSkills.ids().contains(skillId)) {
                skills.add(SkillRepositories.activeSkill(repository, skillId));
            }
        }
        if (skills.isEmpty()) {
            if (activeAgent.skillIds() == null || activeAgent.skillIds().isEmpty()) {
                throw new PayrollException(
                        "智能体「" + activeAgent.name() + "」未绑定技能，无法启动；请先为其绑定角色技能");
            }
            throw new PayrollException(
                    "智能体「" + activeAgent.name() + "」绑定的技能不存在："
                            + activeAgent.skillIds()
                            + "；请先在技能管理中创建或修复，已阻止回退为薪酬计算技能");
        }
        return skills;
    }

    static Set<String> allowedTools(List<String> skillIds) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String skillId : skillIds) {
            if (RoleSkills.CALCULATOR.equals(skillId)) {
                allowed.addAll(CALCULATOR_TOOLS);
            } else if (RoleSkills.VALIDATOR.equals(skillId)) {
                allowed.addAll(VALIDATOR_TOOLS);
            }
        }
        if (allowed.isEmpty() && (skillIds == null || skillIds.isEmpty())) {
            allowed.addAll(CALCULATOR_TOOLS);
        }
        return allowed;
    }

    /** 流程节点工具白名单：计算/验证技能映射业务工具；入账等只读技能不获得计算工具。 */
    private static Set<String> flowTools(List<String> skillIds) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String skillId : skillIds) {
            if (RoleSkills.CALCULATOR.equals(skillId)) {
                allowed.addAll(CALCULATOR_TOOLS);
            } else if (RoleSkills.VALIDATOR.equals(skillId)) {
                allowed.addAll(VALIDATOR_TOOLS);
            }
        }
        return allowed;
    }

    /** 流程节点沿用角色提示词，避免把“第 2/3 环”误当成独立核算智能体。 */
    private static String flowNodePrompt(AgentDefinition agent, List<AgentSkill> skills) {
        List<String> skillIds = agent.skillIds();
        String skillId = skills.get(0).getSkillId();
        if (skillIds.contains(RoleSkills.VALIDATOR)) {
            return validatorPrompt(skillId);
        }
        if (skillIds.contains(RoleSkills.ACCOUNTANT)) {
            return accountantPrompt(skillId);
        }
        if (skillIds.contains(RoleSkills.CALCULATOR)) {
            return calculatorPrompt(skillId);
        }
        return activeAgentPrompt(agent, skills);
    }

    /**
     * 自定义技能智能体的提示词：角色由管理员填写的名称、描述与技能正文组成。
     * 技能正文直接预加载，不依赖运行期 load_skill_through_path，避免小模型失败后
     * 编造技能 id 或把伪工具调用写进正文。
     */
    static String activeAgentPrompt(AgentDefinition agent, List<AgentSkill> skills) {
        String description = agent.description() == null ? "" : agent.description();
        return GENERAL_RULES
                + "\n"
                + skillBlock(skills)
                + """
                ## 基本职责
                你是「%s」。%s
                严格按上方的角色技能处理用户诉求；技能未覆盖的问题不要自行补全数据或流程，
                超出职责范围时直接说明并请用户提出对应问题。
                角色技能已由系统预加载，禁止调用 load_skill_through_path，也不要输出任何工具调用文本。
                """
                        .formatted(agent.name(), description);
    }

    /** 把绑定技能正文拼成系统提示词片段；同名技能只保留第一份。 */
    private static String skillBlock(List<AgentSkill> skills) {
        StringBuilder block = new StringBuilder("## 已加载角色技能（必须遵循）\n");
        int total = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (AgentSkill skill : skills) {
            if (skill == null || !seen.add(skill.getSkillId())) {
                continue;
            }
            String content = skill.getSkillContent() == null ? "" : skill.getSkillContent().strip();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > MAX_SKILL_CONTENT_CHARS) {
                content = content.substring(0, MAX_SKILL_CONTENT_CHARS)
                        + "\n（技能内容过长，已截断，请按上述规则执行）";
            }
            if (total + content.length() > MAX_SKILL_BLOCK_CHARS) {
                break;
            }
            block.append("\n### ")
                    .append(skill.getSkillId())
                    .append("\n")
                    .append(content)
                    .append("\n");
            total += content.length();
        }
        return block.toString();
    }

    /** 薪酬三个内置角色需要动态技能来激活业务工具，自定义角色则改为正文预注入。 */
    private static boolean isPayrollRole(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return false;
        }
        return skillIds.contains(RoleSkills.CALCULATOR)
                || skillIds.contains(RoleSkills.VALIDATOR)
                || skillIds.contains(RoleSkills.ACCOUNTANT);
    }

    /** CLI 的 --extract 模式仍使用单智能体做结构化意图抽取。 */
    public static HarnessAgent createIntentAgent(PayrollTools tools) {
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

    /**
     * 无工具的单轮 JSON 智能体，供动态团队的意图/计划生成使用。
     *
     * <p>maxIters=1 保证不会进入工具循环；调用方负责 10 秒超时与规则兜底。
     */
    public static HarnessAgent createUtilityAgent(
            String name, String sysPrompt, int maxIters) {
        return createAgent(
                name,
                name,
                sysPrompt,
                sharedModel(),
                new Toolkit(),
                Math.max(1, maxIters));
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters) {
        return createAgent(name, description, sysPrompt, model, toolkit, maxIters, null, List.of());
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            String skillId) {
        return createAgent(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                skillId == null ? List.of() : List.of(skillId));
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            List<String> skillIds) {
        AgentSkillRepository scoped =
                skillRepository != null && !skillIds.isEmpty()
                        ? SkillRepositories.scoped(skillRepository, skillIds)
                        : null;
        HarnessAgent agent =
                HarnessAgentSupport.build(
                        name, description, sysPrompt, model, toolkit, maxIters, scoped);
        log.debug(
                "[harness] 智能体已构建：{}，技能={}，工具={}",
                name,
                skillIds,
                agent.getToolkit().getToolNames());
        return agent;
    }

    /**
     * 业务工具先挂到未激活的技能工具组，模型必须先 load_skill_through_path 激活所属技能，
     * 工具才会出现在可用列表中，避免小模型跳过角色技能直接作答。
     *
     * <p>AgentScope 用技能的裸名称（{@link AgentSkill#getName()}）匹配 {@code activateOnSkill}，
     * 而不是带来源后缀的完整 skillId；传错会导致技能加载成功但业务工具始终不可见。
     */
    static Toolkit toolkit(
            PayrollTools tools, Set<String> allowed, String role, AgentSkill skill) {
        Toolkit toolkit = new Toolkit();
        if (allowed.isEmpty()) {
            log.info("[多智能体] {}智能体工具集：[]（角色无需业务工具）", role);
            return toolkit;
        }
        toolkit.createSkillToolGroup(
                "role-tools", role + "业务工具（加载角色技能后启用）", false, skill.getName());
        toolkit.registration().tool(tools).group("role-tools").apply();
        for (String name : Set.copyOf(toolkit.getToolNames())) {
            if (!allowed.contains(name)) {
                toolkit.removeTool(name);
            }
        }
        log.info(
                "[多智能体] {}智能体工具集：{}（加载 {} 后启用）",
                role,
                toolkit.getToolNames(),
                skill.getSkillId());
        return toolkit;
    }

    public static String modelId() {
        String configured = System.getenv("PAYROLL_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    /** 进程级共享模型：构建时会探测 Ollama，所有智能体复用同一次探测结果。 */
    public static Model sharedModel() {
        String modelId = modelId();
        Model model = sharedModel;
        if (model != null && modelId.equals(sharedModelId)) {
            return model;
        }
        synchronized (PayrollAgentFactory.class) {
            if (sharedModel == null || !modelId.equals(sharedModelId)) {
                sharedModel = buildModel(modelId);
                sharedModelId = modelId;
            }
            return sharedModel;
        }
    }

    /** 清空共享模型与智能体缓存；仅用于测试或应用关闭。 */
    public static void resetRuntimeCache() {
        synchronized (PayrollAgentFactory.class) {
            sharedModel = null;
            sharedModelId = null;
            AGENTS.clear();
        }
    }

    /** 当前缓存的智能体定义数量，用于启动/切换日志与排查。 */
    public static int cachedAgentCount() {
        return AGENTS.size();
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
                        // 限制单轮生成长度，避免小模型重复输出直到阶段超时。
                        .numPredict(
                                intEnv(
                                        "PAYROLL_OLLAMA_NUM_PREDICT",
                                        DEFAULT_OLLAMA_NUM_PREDICT))
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
