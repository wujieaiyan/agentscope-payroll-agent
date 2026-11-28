package io.agentscope.examples.payroll.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.examples.payroll.domain.AgentDefinition;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.devteam.DevContextBudget;
import io.agentscope.examples.payroll.devteam.DevEditSession;
import io.agentscope.examples.payroll.devteam.DevGuardMonitor;
import io.agentscope.examples.payroll.devteam.DevModelSettings;
import io.agentscope.examples.payroll.devteam.DevPathPolicy;
import io.agentscope.examples.payroll.devteam.DevRoleContract;
import io.agentscope.examples.payroll.agent.model.FailoverModel;
import io.agentscope.examples.payroll.agent.model.OpenAiCompatibleChatModel;
import io.agentscope.examples.payroll.model.AgentModelSettings;
import io.agentscope.examples.payroll.model.ModelConfigException;
import io.agentscope.examples.payroll.model.ModelConnection;
import io.agentscope.examples.payroll.model.ModelOutboundPolicy;
import io.agentscope.examples.payroll.model.OllamaThinkingModel;
import io.agentscope.examples.payroll.service.KbSkillClient;
import io.agentscope.examples.payroll.service.ModelCredentialService;
import io.agentscope.examples.payroll.skill.ModelConnectionRegistry;
import io.agentscope.examples.payroll.service.KbSkillGateway;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.service.SandboxClient;
import io.agentscope.examples.payroll.service.SandboxGateway;
import io.agentscope.examples.payroll.skill.PayrollSkillCatalog;
import io.agentscope.examples.payroll.skill.RoleSkills;
import io.agentscope.examples.payroll.skill.SkillExecutionSettings;
import io.agentscope.examples.payroll.skill.SkillExecutions;
import io.agentscope.examples.payroll.skill.SkillManifest;
import io.agentscope.examples.payroll.skill.SkillManifests;
import io.agentscope.examples.payroll.skill.SkillMetrics;
import io.agentscope.examples.payroll.skill.SkillRepositories;
import io.agentscope.examples.payroll.usage.UsageModelCatalog;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组装薪酬多智能体：计算、验证、核算三个角色共享同一模型，但使用不同的系统提示词与工具集。
 *
 * <p>流水线顺序由 {@code PayrollChatService} 固定编排，模型只负责理解、调用工具与解释结果；
 * 金额计算始终由 {@link io.agentscope.examples.payroll.service.PayrollEngine} 完成。
 */
public final class PayrollAgentFactory {

    public static final String DEFAULT_MODEL = io.agentscope.examples.payroll.support.ModelEnv.DEFAULT_MODEL;
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private static final Logger log = LoggerFactory.getLogger(PayrollAgentFactory.class);
    private static final int DEFAULT_OLLAMA_NUM_CTX = 8192;
    private static final int DEFAULT_OLLAMA_NUM_PREDICT = 1024;
    private static final double DEFAULT_OLLAMA_TEMPERATURE = 0.1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MODEL_CACHE_MAX = 16;

    private static final AgentCache AGENTS = new AgentCache();
    /** 每个逻辑缓存的最近一次定义/模型签名，用于区分技能变化与模型变化导致的重建。 */
    private static final Map<String, String[]> LAST_BUILD = new ConcurrentHashMap<>();
    /** 连接版本进缓存键：连接改模型/地址/参数即重建，多面共用同一上限。 */
    private static final Map<String, Model> MODELS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Model> eldest) {
                    return size() > MODEL_CACHE_MAX;
                }
            };
    private static volatile ModelConnectionRegistry modelRegistry = ModelConnectionRegistry.shared();
    private static volatile ModelCredentialService modelCredentials;
    private static volatile ModelOutboundPolicy modelOutboundPolicy =
            ModelOutboundPolicy.fromEnvironment();

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

    private static final String COMMON_RULES = PromptLibrary.get("common-rules");

    private static final String SKILL_BOOT_RULE = PromptLibrary.get("skill-boot-rule");

    /** 自定义技能智能体的通用规则：不复用薪酬口径，避免把非薪酬角色拉回核算场景。 */
    private static final String GENERAL_RULES = PromptLibrary.get("general-rules");

    /** 只读检索规则：协同智能体与团队/流程 KB 节点共用。 */
    private static final String KB_SEARCH_GUIDANCE = PromptLibrary.get("kb-search-guidance");

    /** 知识库管理与配置能力：仅声明 kb:config 的智能体可见，团队/流程节点与其他自定义智能体不注册写工具。 */
    private static final String KB_CONFIG_GUIDANCE = PromptLibrary.get("kb-config-guidance");

    /** 静态流水线只有计算智能体面向用户提问：校验与核算阶段没有交互工具，也不该再追问。 */
    private static final String PIPELINE_INTERACTION_RULES =
            PromptLibrary.get("pipeline-interaction-rules");

    private static final String CALCULATOR_RESPONSIBILITY =
            PromptLibrary.get("calculator-responsibility");

    private static final String VALIDATOR_RESPONSIBILITY =
            PromptLibrary.get("validator-responsibility");

    private static final String ACCOUNTANT_RESPONSIBILITY =
            PromptLibrary.get("accountant-responsibility");

    private static final String KB_AGENT_RESPONSIBILITY =
            PromptLibrary.get("kb-agent-responsibility");

    private static final String ACTIVE_AGENT_RESPONSIBILITY =
            PromptLibrary.get("active-agent-responsibility");

    private static String calculatorPrompt(String skillId, String skillPrompt) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + skillPrompt
                + PIPELINE_INTERACTION_RULES
                + CALCULATOR_RESPONSIBILITY;
    }

    private static String validatorPrompt(String skillId, String skillPrompt) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + skillPrompt
                + VALIDATOR_RESPONSIBILITY;
    }

    private static String accountantPrompt(String skillId, String skillPrompt) {
        return COMMON_RULES
                + "\n"
                + SKILL_BOOT_RULE.formatted(skillId)
                + skillPrompt
                + ACCOUNTANT_RESPONSIBILITY;
    }

    private static final String SUMMARIZER_PROMPT = PromptLibrary.get("context-summarizer");

    private static final String INTENT_PROMPT = PromptLibrary.get("intent-extractor");

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
        String logical = "team|" + runtimeKey(engine);
        String definition =
                isPipeline(activeAgent) ? "pipeline" : definitionSignature(activeAgent);
        String model = modelKey(activeAgent);
        String key = logical + "|" + definition + "|" + model;
        return AGENTS.get(
                key,
                () -> createTeam(new PayrollTools(engine), activeAgent),
                () -> noteAgentRebuild(logical, definition, model));
    }

    /** 流程节点按智能体定义复用；节点级会话隔离由调用方的 RuntimeContext 保证。 */
    public static NodeAgent sharedFlowNodeAgent(PayrollEngine engine, AgentDefinition agent) {
        return sharedFlowNodeAgent(engine, agent, false);
    }

    /**
     * @param kbSearch 节点是否注册只读 kb_search；不同取值使用独立缓存键，避免共享到写工具
     */
    public static NodeAgent sharedFlowNodeAgent(
            PayrollEngine engine, AgentDefinition agent, boolean kbSearch) {
        String logical = "node|" + runtimeKey(engine);
        String definition = definitionSignature(agent);
        String model = modelKey(agent);
        String key = logical + "|" + definition + "|" + model + (kbSearch ? "|kb" : "");
        return AGENTS.get(
                key,
                () ->
                        createFlowNodeAgent(
                                new PayrollTools(engine), agent, controlModel(agent.model()), kbSearch),
                () -> noteAgentRebuild(logical, definition, model));
    }

    /**
     * 制度问答兜底：默认安装可能没有任何知识库角色/流程，制度类问题仍需检索而非掉回薪酬流水线。
     *
     * <p>只注册 {@link KbQueryTools}，不暴露任何写工具；内部令牌缺失时返回 {@code null}，
     * 调用方保持原链路。
     */
    public static HarnessAgent sharedKbAgent(PayrollEngine engine) {
        KbSkillGateway gateway = kbGateway();
        if (gateway == null) {
            return null;
        }
        String logical = "kb|" + runtimeKey(engine);
        String model = modelKey(null);
        return AGENTS.get(
                logical + "|" + model,
                () -> createKbAgent(sharedModel(), gateway),
                () -> noteAgentRebuild(logical, "kb", model));
    }

    /**
     * 记录智能体缓存重建原因（规格 4.7）：模型连接版本变化优先归为 model，其次技能/定义
     * 指纹变化归为 skill，首次构建与空闲淘汰后的重建归为 initial/other。
     */
    private static void noteAgentRebuild(String logical, String definition, String model) {
        String[] previous = LAST_BUILD.put(logical, new String[] {definition, model});
        String reason;
        if (previous == null) {
            reason = "initial";
        } else if (!previous[1].equals(model)) {
            reason = "model";
        } else if (!previous[0].equals(definition)) {
            reason = "skill";
        } else {
            reason = "other";
        }
        SkillMetrics.agentCacheRebuild(reason);
    }

    private static HarnessAgent createKbAgent(Model model, KbSkillGateway gateway) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new KbQueryTools(gateway));
        toolkit.registerTool(new ConfirmationTools());
        toolkit.registerTool(new QuestionTools());
        return createAgent(
                "PayrollKbAgent",
                "知识库问答智能体：只读检索全部可读知识库并带引用作答",
                GENERAL_RULES
                        + "\n"
                        + KB_SEARCH_GUIDANCE
                        + KB_AGENT_RESPONSIBILITY,
                model,
                toolkit,
                8);
    }

    /**
     * 只读检索注册入口（规格 6.3）：声明 kb:read 的智能体/流程节点才挂 {@link KbQueryTools}；
     * 未声明时按角色与标签回退到旧规则，写工具永不由这里注册。
     */
    public static boolean kbSearchNode(String role, AgentDefinition agent) {
        return AgentCapabilities.supportsKbRead(role, agent);
    }

    /**
     * 知识库管理工具注册入口：只有显式声明 kb:config 的智能体才挂 {@link KbTools}；
     * 自定义智能体（诗词、办公、情感等）不再被动获得 13 个管理/写工具。
     */
    public static boolean kbConfigAgent(AgentDefinition agent) {
        return AgentCapabilities.supportsKbConfig(agent);
    }

    /**
     * 生产环境只有一个 {@link PayrollEngine}；把引擎身份纳入 key，避免测试或嵌入场景
     * 复用另一个引擎上的工具实例。
     */
    private static String runtimeKey(PayrollEngine engine) {
        return engine == null ? "none" : "engine@" + System.identityHashCode(engine);
    }

    /** 定义签名：任一字段变化都会生成新的缓存条目；同时写入路由快照供事后解释。 */
    public static String definitionSignature(AgentDefinition agent) {
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
                + agent.capabilities()
                + "|"
                + agent.tools()
                + "|"
                + settingsFingerprint(agent.model())
                + "|"
                + skillFingerprint(agent.skillIds());
    }

    /**
     * 智能体缓存键里的模型段：解析后的连接版本，而不是原始设置。
     * 页面改动连接地址/参数会让版本号变化，从而让旧智能体与其模型实例一起失效。
     */
    private static String modelKey(AgentDefinition agent) {
        AgentModelSettings settings =
                agent == null || agent.model() == null
                        ? AgentModelSettings.none()
                        : agent.model();
        try {
            return modelRegistry.resolveControl(settings).versionKey();
        } catch (RuntimeException e) {
            // 解析失败时保留可见差异，真正的失败仍由构建模型时抛出。
            return "invalid|" + settingsFingerprint(settings);
        }
    }

    private static String settingsFingerprint(AgentModelSettings settings) {
        if (settings == null || settings.isEmpty()) {
            return "";
        }
        return settings.connectionId()
                + ':' + settings.model()
                + ':' + settings.numCtx()
                + ':' + settings.numPredict()
                + ':' + settings.temperature()
                + ':' + settings.timeoutSeconds();
    }

    /** 共享仓库视图下的技能指纹，见 {@link #skillFingerprint(AgentSkillRepository, List)}。 */
    private static String skillFingerprint(List<String> skillIds) {
        return skillFingerprint(SkillRepositories.shared(), skillIds);
    }

    /**
     * 仓库视图下的技能指纹：正文哈希、资源指纹与执行开关任一变化都必须让旧的系统提示词与
     * 工具集失效。
     *
     * <p>资源是烤进 {@link AgentSkill} 的，只有装配过正文才看得到（{@code getResources()}
     * 内存态），因此签名前先取一次技能，让仓库把资源指纹算好，再读 manifest 的预计算哈希：
     * 每轮对话只付一次缓存查找，不必重新哈希整个文件包。少了这一步，只改参考文件时
     * 指纹不变，已缓存智能体会让模型继续读旧文件，直到条目被淘汰。
     */
    static String skillFingerprint(AgentSkillRepository repository, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return "";
        }
        PayrollSkillCatalog catalog =
                repository instanceof PayrollSkillCatalog candidate ? candidate : null;
        StringBuilder fingerprint = new StringBuilder();
        for (String skillId : new LinkedHashSet<>(skillIds)) {
            AgentSkill skill = repository.getSkill(skillId);
            SkillManifest value =
                    catalog != null
                            ? catalog.manifest(skillId).orElse(null)
                            : SkillManifests.fromSkill(skill);
            if (value == null && skill != null) {
                value = SkillManifests.fromSkill(skill);
            }
            fingerprint.append(skillId).append(':');
            if (value == null) {
                fingerprint.append("missing");
            } else {
                fingerprint.append(value.source())
                        .append(':')
                        .append(value.contentHash())
                        .append(':')
                        .append(value.resourceHash())
                        .append(':')
                        .append(value.executionEnabled() ? "exec" : "");
            }
            fingerprint.append(';');
        }
        return fingerprint.toString();
    }

    public static Team createTeam(PayrollTools tools, AgentDefinition activeAgent) {
        Model model =
                controlModel(
                        activeAgent == null
                                ? AgentModelSettings.none()
                                : activeAgent.model());
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
        int[] skillBudget = skillBudgetParams(null);
        Toolkit calculatorTools = toolkit(tools, CALCULATOR_TOOLS, "计算", calculatorSkill);
        calculatorTools.registerTool(new ConfirmationTools());
        calculatorTools.registerTool(new QuestionTools());
        String calculatorSkillPrompt =
                wireSkills(
                        List.of(calculatorSkill),
                        calculatorTools,
                        skillBudget[0],
                        skillBudget[1],
                        "薪酬计算");
        Toolkit validatorTools = toolkit(tools, VALIDATOR_TOOLS, "验证", validatorSkill);
        String validatorSkillPrompt =
                wireSkills(
                        List.of(validatorSkill),
                        validatorTools,
                        skillBudget[0],
                        skillBudget[1],
                        "薪酬验证");
        Toolkit accountantTools = new Toolkit();
        String accountantSkillPrompt =
                wireSkills(
                        List.of(accountantSkill),
                        accountantTools,
                        skillBudget[0],
                        skillBudget[1],
                        "薪酬核算");
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
                        calculatorPrompt(calculatorSkill.getSkillId(), calculatorSkillPrompt),
                        model,
                        calculatorTools,
                        12,
                        skillRepository,
                        RoleSkills.CALCULATOR),
                createAgent(
                        "PayrollValidatorAgent",
                        "薪酬验证智能体：独立调用发薪前校验工具，复核勾稽关系与风险项",
                        validatorPrompt(validatorSkill.getSkillId(), validatorSkillPrompt),
                        model,
                        validatorTools,
                        8,
                        skillRepository,
                        RoleSkills.VALIDATOR),
                createAgent(
                        "PayrollAccountantAgent",
                        "薪酬核算智能体：汇总计算与验证记录，生成最终可审计答复",
                        accountantPrompt(accountantSkill.getSkillId(), accountantSkillPrompt),
                        model,
                        accountantTools,
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
        return createFlowNodeAgent(tools, agent, model, false);
    }

    /**
     * @param kbSearch 只注册只读 {@link KbQueryTools}；绝不向流程节点暴露知识库写工具
     */
    public static NodeAgent createFlowNodeAgent(
            PayrollTools tools, AgentDefinition agent, Model model, boolean kbSearch) {
        AgentSkillRepository skillRepository = SkillRepositories.shared();
        List<AgentSkill> skills = boundSkills(skillRepository, agent);
        AgentSkill primary = skills.get(0);
        boolean payrollRole = isPayrollRole(agent.skillIds());
        Optional<String> devRole = devRole(agent);
        Set<String> allowed = flowTools(agent);
        Toolkit toolkit = toolkit(tools, allowed, agent.name(), primary);
        int[] skillBudget = skillBudgetParams(agent);
        String skillPrompt =
                wireSkills(
                        skills,
                        toolkit,
                        skillBudget[0],
                        skillBudget[1],
                        skillFocus(agent));
        KbSkillGateway kbGateway = kbSearch ? kbGateway() : null;
        if (kbGateway != null) {
            toolkit.registerTool(new KbQueryTools(kbGateway));
        }
        if (!payrollRole) {
            toolkit.registerTool(new ConfirmationTools());
            toolkit.registerTool(new QuestionTools());
        }
        List<McpClientWrapper> mcpClients =
                McpToolRegistrar.registerAll(
                        toolkit,
                        McpServerConfig.fromEnvironment(),
                        McpToolRegistrar.defaultFactory());
        String prompt = flowNodePrompt(agent, skills, skillPrompt);
        if (kbGateway != null) {
            prompt = prompt + "\n" + KB_SEARCH_GUIDANCE;
        }
        Path projectRoot = devRole.isPresent() ? devProjectRoot() : null;
        prompt = withDevBriefing(prompt, devRole.orElse(null));
        HarnessAgent nodeAgent =
                createAgent(
                        "FlowNode_" + agent.id(),
                        agent.name() + "：" + (agent.description() == null ? "" : agent.description()),
                        prompt,
                        model,
                        toolkit,
                        12,
                        // 统一契约（S3）：技能正文按预算预注入，scoped 仓库始终传入，
                        // load_skill_through_path 作为目录中未注入技能的兜底入口。
                        skillRepository,
                        agent.skillIds(),
                        devRole.orElse(null),
                        projectRoot,
                        devRole.isPresent() ? devContextBudget() : null);
        if (kbSearch) {
            log.info(
                    "[流程节点] 知识库检索：{}（{}），工具={}",
                    agent.name(),
                    agent.id(),
                    toolkit.getToolNames());
        }
        return new NodeAgent(nodeAgent, mcpClients);
    }

    /** 任务中心的研发任务规则：写入被限定在工作区，结果由系统统一快照。 */
    private static final String DEV_TASK_RULES = PromptLibrary.get("dev-task-rules");

    /**
     * 研发任务执行（P1-2）：按角色技能与交接契约构建智能体；传入编辑会话时可写隔离工作区。
     *
     * <p>写权限仍由 {@link HarnessAgentSupport} 依据 {@link DevPathPolicy} 二次限制，
     * 会话只决定文件工具与工作区根目录。编码面模型独立于控制面（P1-6）：写任务必须使用
     * {@code PAYROLL_DEV_MODEL}，未配置时直接失败，绝不回落到控制面模型生成补丁。
     */
    public static HarnessAgent createDevTaskAgent(
            String role, String ownerAgent, DevEditSession session) {
        return createDevTaskAgent(role, ownerAgent, session, null);
    }

    /**
     * 任务中心执行入口：{@code guard} 非空时由文件系统登记护栏命中，
     * 运行器在任务收尾统一判定 fail-closed。
     */
    public static HarnessAgent createDevTaskAgent(
            String role,
            String ownerAgent,
            DevEditSession session,
            DevGuardMonitor guard) {
        DevPathPolicy.RolePaths paths =
                DevPathPolicy.role(role)
                        .orElseThrow(() -> new PayrollException("未知研发角色：" + role));
        DevRoleContract contract =
                DevRoleContract.of(paths.role())
                        .orElseThrow(() -> new PayrollException("缺少研发角色契约：" + role));
        AgentSkillRepository repository = SkillRepositories.shared();
        AgentSkill skill = repository.getSkill(paths.role());
        if (skill == null) {
            throw new PayrollException(
                    "研发角色技能未发布：" + paths.role() + "；请先在技能管理中发布后再执行任务");
        }
        DevModelSettings devSettings = DevModelSettings.fromEnvironment();
        String prompt =
                GENERAL_RULES
                        + "\n"
                        + skillBlock(List.of(skill), devSettings.numCtx(), devSettings.numPredict())
                        + SkillReadTools.guidance(List.of(skill))
                        + contract.renderBriefing()
                        + "\n"
                        + DEV_TASK_RULES;
        Model model = selectDevModel(devSettings, session != null);
        Toolkit toolkit = new Toolkit();
        if (SkillReadTools.hasResources(List.of(skill))) {
            toolkit.registerTool(new SkillReadTools(List.of(skill)));
        }
        Path projectRoot =
                session != null ? session.workspace().root() : devProjectRoot();
        if (paths.readEnabled()) {
            toolkit.registerTool(new DevCodeIndexTools(projectRoot));
        }
        HarnessAgent agent =
                createAgent(
                        "DevTask_" + paths.role(),
                        contract.displayName()
                                + "："
                                + (ownerAgent == null || ownerAgent.isBlank()
                                        ? "研发任务执行"
                                        : ownerAgent),
                        prompt,
                        model,
                        toolkit,
                        24,
                        null,
                        List.of(),
                        paths.role(),
                        projectRoot,
                        devContextBudget(),
                        session,
                        guard);
        log.info(
                "[开发域] 任务智能体已构建：role={}，owner={}，工作区={}，写模式={}，工具={}",
                paths.role(),
                ownerAgent,
                projectRoot,
                session != null,
                agent.getToolkit().getToolNames());
        return agent;
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
        Set<String> allowed = allowedTools(activeAgent);
        Toolkit toolkit = toolkit(tools, allowed, activeAgent.name(), primary);
        int[] skillBudget = skillBudgetParams(activeAgent);
        String skillPrompt =
                wireSkills(
                        boundSkills,
                        toolkit,
                        skillBudget[0],
                        skillBudget[1],
                        skillFocus(activeAgent));
        KbSkillGateway kbGateway = kbGateway();
        // 知识库管理/写工具只给显式声明 kb:config 的智能体，其余智能体不注册，避免越权写入口与工具集膨胀。
        boolean kbConfig = kbGateway != null && kbConfigAgent(activeAgent);
        KbTools kbTools = kbConfig ? new KbTools(kbGateway) : null;
        if (kbTools != null) {
            toolkit.registerTool(kbTools);
        }
        // 规格 6.3：kb_search 只挂给具备 kb:read 的智能体，其余智能体不获得检索入口。
        boolean kbRead = kbGateway != null && AgentCapabilities.supportsKbRead(activeAgent);
        if (kbRead) {
            toolkit.registerTool(new KbQueryTools(kbGateway));
        }
        toolkit.registerTool(new ConfirmationTools());
        toolkit.registerTool(new QuestionTools());
        List<McpClientWrapper> mcpClients =
                McpToolRegistrar.registerAll(
                        toolkit,
                        McpServerConfig.fromEnvironment(),
                        McpToolRegistrar.defaultFactory());
        log.info(
                "[Agent] 核算智能体：{}（{}），技能={}，工具={}，知识库工具={}，只读检索={}",
                activeAgent.name(),
                activeAgent.id(),
                activeAgent.skillIds(),
                toolkit.getToolNames(),
                kbTools != null,
                kbRead);
        StringBuilder prompt = new StringBuilder(activeAgentPrompt(activeAgent, skillPrompt));
        if (kbRead) {
            prompt.append('\n').append(KB_SEARCH_GUIDANCE);
        }
        if (kbTools != null) {
            prompt.append('\n').append(KB_CONFIG_GUIDANCE);
        }
        String promptText = prompt.toString();
        Optional<String> devRole = devRole(activeAgent);
        Path projectRoot = devRole.isPresent() ? devProjectRoot() : null;
        promptText = withDevBriefing(promptText, devRole.orElse(null));
        HarnessAgent agent =
                createAgent(
                        "PayrollActiveAgent",
                        activeAgent.name() + "：" + activeAgent.description(),
                        promptText,
                        model,
                        toolkit,
                        12,
                        // 统一契约（S3）：正文已按预算预注入，scoped 仓库仍传入，
                        // load_skill_through_path 只作为目录中未注入技能的兜底入口。
                        skillRepository,
                        activeAgent.skillIds(),
                        devRole.orElse(null),
                        projectRoot,
                        devRole.isPresent() ? devContextBudget() : null);
        return new Team(agent, null, null, summarizer, mcpClients, activeAgent, false);
    }

    /** 未配置内部令牌或显式关闭时不注册，避免模型看到永远失败的工具。 */
    private static KbSkillGateway kbGateway() {
        KbSkillGateway gateway = KbSkillClient.fromEnvironment();
        return gateway.enabled() ? gateway : null;
    }

    /** 沙箱地址或令牌缺失时返回 null，与 KB 通道一致，不注册必然失败的工具。 */
    private static SandboxGateway sandboxGateway() {
        SandboxGateway gateway = SandboxClient.fromEnvironment();
        return gateway.enabled() ? gateway : null;
    }

    private static int skillExecutionTimeoutMs() {
        return SkillExecutionSettings.fromEnvironment().timeoutMs();
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

    /**
     * 声明了 {@code tools} 的智能体以声明为唯一白名单；未声明时沿用技能映射，
     * 保证存量定义行为不变。
     */
    static Set<String> allowedTools(AgentDefinition agent) {
        Set<String> declared = declaredTools(agent);
        return declared.isEmpty() ? allowedTools(agent.skillIds()) : declared;
    }

    /** 流程节点同上：声明优先，未声明才按技能映射。 */
    private static Set<String> flowTools(AgentDefinition agent) {
        Set<String> declared = declaredTools(agent);
        return declared.isEmpty() ? flowTools(agent.skillIds()) : declared;
    }

    private static Set<String> declaredTools(AgentDefinition agent) {
        if (agent == null || agent.tools().isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(agent.tools());
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
    private static String flowNodePrompt(
            AgentDefinition agent, List<AgentSkill> skills, String skillPrompt) {
        List<String> skillIds = agent.skillIds();
        String skillId = skills.get(0).getSkillId();
        if (skillIds.contains(RoleSkills.VALIDATOR)) {
            return validatorPrompt(skillId, skillPrompt);
        }
        if (skillIds.contains(RoleSkills.ACCOUNTANT)) {
            return accountantPrompt(skillId, skillPrompt);
        }
        if (skillIds.contains(RoleSkills.CALCULATOR)) {
            return calculatorPrompt(skillId, skillPrompt);
        }
        return activeAgentPrompt(agent, skillPrompt);
    }

    /**
     * 自定义技能智能体的提示词：角色由管理员填写的名称、描述与技能正文组成。
     * 技能正文直接预加载，不依赖运行期 load_skill_through_path，避免小模型失败后
     * 编造技能 id 或把伪工具调用写进正文。
     */
    static String activeAgentPrompt(AgentDefinition agent, List<AgentSkill> skills) {
        int[] params = skillBudgetParams(agent);
        return activeAgentPrompt(agent, skills, params[0], params[1]);
    }

    /** 按已解析模型窗口构建提示词：预算不足时技能正文降级为目录，技能不会静默消失。 */
    static String activeAgentPrompt(
            AgentDefinition agent, List<AgentSkill> skills, int contextWindow, int numPredict) {
        return activeAgentPrompt(
                agent, skillContract(skills, contextWindow, numPredict, skillFocus(agent)));
    }

    /** 技能片段由统一装配器产出（预算内预注入 + 目录 + 资源指引），此处只拼职责。 */
    static String activeAgentPrompt(AgentDefinition agent, String skillPrompt) {
        String description = agent.description() == null ? "" : agent.description();
        return GENERAL_RULES
                + "\n"
                + skillPrompt
                + ACTIVE_AGENT_RESPONSIBILITY.formatted(agent.name(), description);
    }

    /** 解析控制面模型的有效窗口与生成预留，供技能预算使用。 */
    private static int[] skillBudgetParams(AgentDefinition agent) {
        AgentModelSettings settings =
                agent == null || agent.model() == null
                        ? AgentModelSettings.none()
                        : agent.model();
        int numCtx = DEFAULT_OLLAMA_NUM_CTX;
        int numPredict = DEFAULT_OLLAMA_NUM_PREDICT;
        try {
            ModelConnection connection = modelRegistry.resolveControl(settings);
            numCtx = connection.numCtx(numCtx);
            numPredict = connection.numPredict(numPredict);
        } catch (RuntimeException e) {
            // 模型未配置时按默认窗口计算预算，真正的失败仍由构建模型时抛出。
        }
        return new int[] {numCtx, numPredict};
    }

    /** 关联文本：智能体名称与描述决定绑定技能的相关度排序（规格 4.3 目录收敛）。 */
    private static String skillFocus(AgentDefinition agent) {
        if (agent == null) {
            return null;
        }
        String name = agent.name() == null ? "" : agent.name();
        String description = agent.description() == null ? "" : agent.description();
        return name + " " + description;
    }

    /** 把绑定技能正文拼成系统提示词片段；同名技能只保留第一份。 */
    private static String skillBlock(List<AgentSkill> skills) {
        int[] params = skillBudgetParams(null);
        return skillBlock(skills, params[0], params[1]);
    }

    /** 技能正文按上下文预算贪心注入，放不下的技能转为目录行并记录日志。 */
    private static String skillBlock(List<AgentSkill> skills, int contextWindow, int numPredict) {
        return skillBlock(skills, contextWindow, numPredict, null);
    }

    private static String skillBlock(
            List<AgentSkill> skills, int contextWindow, int numPredict, String focus) {
        SkillPromptBudget.Result result =
                SkillPromptBudget.build(skills, contextWindow, numPredict, focus);
        log.info(
                "[Skill] 提示词技能预算：窗口={}，生成预留={}，可用={} tokens，注入={}，目录={}",
                contextWindow,
                numPredict,
                result.budgetTokens(),
                result.injectedIds(),
                result.catalogIds());
        return result.block();
    }

    /**
     * 统一技能装配（S3）：预算内预注入正文 + 目录，资源/脚本工具在同一处注册，
     * 返回要拼进系统提示词的技能片段。scoped 仓库由调用方随 {@code createAgent} 传入，
     * 因此 {@code load_skill_through_path} 对所有智能体都可用，只作目录中未注入技能的兜底。
     */
    static String wireSkills(
            List<AgentSkill> skills, Toolkit toolkit, int contextWindow, int numPredict, String focus) {
        if (SkillReadTools.hasResources(skills)) {
            toolkit.registerTool(new SkillReadTools(skills));
        }
        String prompt = skillContract(skills, contextWindow, numPredict, focus);
        SandboxGateway sandbox = sandboxGateway();
        if (SkillRunTools.available(skills, sandbox)) {
            toolkit.registerTool(new SkillRunTools(skills, sandbox, skillExecutionTimeoutMs()));
            prompt = prompt + "\n" + SkillRunTools.guidance(skills, skillExecutionTimeoutMs());
        }
        return prompt;
    }

    /** 两级加载的第一级：预算内正文 + 目录；第二级 load_skill 由 scoped 仓库提供。 */
    private static String skillContract(
            List<AgentSkill> skills, int contextWindow, int numPredict, String focus) {
        return skillBlock(skills, contextWindow, numPredict, focus) + SkillReadTools.guidance(skills);
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

    /** 研发域角色识别：智能体 id 或绑定的技能 id 命中九角色之一。 */
    static Optional<String> devRole(AgentDefinition agent) {
        if (agent == null) {
            return Optional.empty();
        }
        Optional<DevPathPolicy.RolePaths> byId = DevPathPolicy.role(agent.id());
        if (byId.isPresent()) {
            return Optional.of(byId.get().role());
        }
        if (agent.skillIds() != null) {
            for (String skillId : agent.skillIds()) {
                Optional<DevPathPolicy.RolePaths> bySkill = DevPathPolicy.role(skillId);
                if (bySkill.isPresent()) {
                    return Optional.of(bySkill.get().role());
                }
            }
        }
        return Optional.empty();
    }

    /** 研发仓库根：环境变量优先，其次系统属性，最后取工作目录；不可用时返回 null（fail-closed）。 */
    static Path devProjectRoot() {
        String configured = System.getenv("PAYROLL_DEV_PROJECT_ROOT");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("payroll.dev-team.project-root");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.dir");
        }
        try {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                return root;
            }
            log.warn("[开发域] 项目根目录不存在，只读文件工具不启用：{}", root);
        } catch (RuntimeException e) {
            log.warn("[开发域] 项目根目录配置非法，只读文件工具不启用：{}", configured);
        }
        return null;
    }

    /**
     * 研发域上下文预算（P0-9）：编码模型可用时按其窗口计算，否则按控制面窗口计算，
     * 保证压缩触发点不会高于实际可用窗口。
     */
    static DevContextBudget devContextBudget() {
        try {
            DevModelSettings dev = DevModelSettings.fromEnvironment();
            if (dev.writeCapable()) {
                return DevContextBudget.of(dev);
            }
        } catch (RuntimeException e) {
            log.warn("[开发域] 编码模型配置非法，按控制面窗口计算上下文预算：{}", e.getMessage());
        }
        return DevContextBudget.of(
                intEnv("PAYROLL_OLLAMA_NUM_CTX", DEFAULT_OLLAMA_NUM_CTX),
                intEnv("PAYROLL_OLLAMA_NUM_PREDICT", DEFAULT_OLLAMA_NUM_PREDICT));
    }

    private static String withDevBriefing(String prompt, String devRole) {
        if (devRole == null) {
            return prompt;
        }
        return DevRoleContract.of(devRole)
                .map(contract -> prompt + "\n\n" + contract.renderBriefing())
                .orElse(prompt);
    }

    /** CLI 的 --extract 模式仍使用单智能体做结构化意图抽取。 */
    public static HarnessAgent createIntentAgent(PayrollTools tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return createAgent(
                "PayrollIntentAgent",
                "薪酬意图抽取智能体：把自然语言解析为结构化意图",
                INTENT_PROMPT,
                controlModel(AgentModelSettings.none()),
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
                utilityModel(),
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
        return createAgent(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                skillIds,
                null,
                null);
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            List<String> skillIds,
            String devRole,
            Path projectRoot) {
        return createAgent(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                skillIds,
                devRole,
                projectRoot,
                null);
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            List<String> skillIds,
            String devRole,
            Path projectRoot,
            DevContextBudget budget) {
        return createAgent(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                skillIds,
                devRole,
                projectRoot,
                budget,
                null);
    }

    /** 绑定任务编辑会话的研发智能体：文件工具可写，范围限定在隔离工作区。 */
    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            List<String> skillIds,
            String devRole,
            Path projectRoot,
            DevContextBudget budget,
            DevEditSession editSession) {
        return createAgent(
                name,
                description,
                sysPrompt,
                model,
                toolkit,
                maxIters,
                skillRepository,
                skillIds,
                devRole,
                projectRoot,
                budget,
                editSession,
                null);
    }

    private static HarnessAgent createAgent(
            String name,
            String description,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            int maxIters,
            AgentSkillRepository skillRepository,
            List<String> skillIds,
            String devRole,
            Path projectRoot,
            DevContextBudget budget,
            DevEditSession editSession,
            DevGuardMonitor guard) {
        AgentSkillRepository scoped =
                skillRepository != null && !skillIds.isEmpty()
                        ? SkillRepositories.scoped(skillRepository, skillIds)
                        : null;
        HarnessAgent agent =
                HarnessAgentSupport.build(
                        name,
                        description,
                        sysPrompt,
                        model,
                        toolkit,
                        maxIters,
                        scoped,
                        devRole,
                        projectRoot,
                        budget,
                        editSession,
                        guard);
        log.debug(
                "[harness] 智能体已构建：{}，技能={}，研发角色={}，工具={}",
                name,
                skillIds,
                devRole,
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
        // 声明合法但运行期没有对应实现（例如 Nacos JSON 绕过控制台校验）时只告警，
        // 不因单个工具缺失拒绝整个智能体。
        Set<String> missing = new LinkedHashSet<>(allowed);
        missing.removeAll(toolkit.getToolNames());
        if (!missing.isEmpty()) {
            log.warn("[多智能体] {}智能体声明了未注册的业务工具：{}", role, missing);
        }
        log.info(
                "[多智能体] {}智能体工具集：{}（加载 {} 后启用）",
                role,
                toolkit.getToolNames(),
                skill.getSkillId());
        return toolkit;
    }

    /** 当前控制面模型标识（provider:model）；解析失败时回落环境变量标签。 */
    public static String modelId() {
        try {
            ModelConnection connection = modelRegistry.resolveControl(AgentModelSettings.none());
            String model = connection.model();
            return model.isBlank() ? connection.provider() : connection.provider() + ":" + model;
        } catch (RuntimeException e) {
            return io.agentscope.examples.payroll.support.ModelEnv.modelId();
        }
    }

    /**
     * 由 Spring 装配注入模型注册表、凭证服务与出网策略；未注入时使用环境变量派生的默认实现。
     */
    public static void configureModels(
            ModelConnectionRegistry registry,
            ModelCredentialService credentials,
            ModelOutboundPolicy policy) {
        synchronized (PayrollAgentFactory.class) {
            modelRegistry = registry == null ? ModelConnectionRegistry.shared() : registry;
            modelCredentials = credentials;
            modelOutboundPolicy =
                    policy == null ? ModelOutboundPolicy.fromEnvironment() : policy;
            MODELS.clear();
            UsageModelCatalog.clear();
        }
    }

    /** 控制面共享模型：按「智能体绑定 > 页面默认 > PAYROLL_MODEL」解析。 */
    public static Model sharedModel() {
        return controlModel(AgentModelSettings.none());
    }

    /** 控制面模型：可按智能体级覆盖解析；连接版本进入缓存键。 */
    public static Model controlModel(AgentModelSettings settings) {
        return cachedModel(modelRegistry.resolveControl(settings), "控制面");
    }

    /** 轻量面模型：意图/计划 JSON；默认复用控制面连接。 */
    public static Model utilityModel() {
        return cachedModel(modelRegistry.resolveUtility(), "轻量面");
    }

    /** 轻量面连接版本：调用方据此判断是否需要重建轻量模型实例。 */
    public static String utilityVersionKey() {
        ModelConnection connection = modelRegistry.resolveUtility();
        return connection.versionKey(credentialVersion(connection));
    }

    /**
     * 编码面模型：{@code PAYROLL_DEV_*} 优先，未设置时用页面 coding 默认；
     * 不满足 32768 上下文或未声明 tools 时返回 null，由 {@link #selectDevModel} 处理。
     */
    static Model devModel(DevModelSettings settings) {
        Optional<ModelConnection> resolved = modelRegistry.resolveCoding();
        if (resolved.isEmpty()) {
            return null;
        }
        ModelConnection connection = resolved.get();
        if (!writeCapable(connection)) {
            return null;
        }
        return cachedModel(connection, "编码面");
    }

    private static boolean writeCapable(ModelConnection connection) {
        return connection.numCtx(0) >= DevModelSettings.MIN_CONTEXT
                && connection.declares(ModelConnection.CAP_TOOLS);
    }

    /**
     * 模型选择（P1-6）：写模式必须有编码面模型，缺失直接失败；只读任务优先用编码面模型，
     * 未配置时回落控制面模型（只读能力不涉及补丁生成）。
     */
    static Model selectDevModel(DevModelSettings settings, boolean writable) {
        Model candidate = devModel(settings);
        if (candidate != null) {
            return candidate;
        }
        if (writable) {
            String reason;
            if (settings != null && settings.disabledReason().isPresent()) {
                reason = settings.disabledReason().get();
            } else if (modelRegistry.envCodingLocked()) {
                reason = "环境变量编码模型未就绪";
            } else {
                reason =
                        "未配置编码面模型（需上下文 ≥"
                                + DevModelSettings.MIN_CONTEXT
                                + " 且声明 tools）";
            }
            throw new PayrollException(
                    "无法执行写代码任务："
                            + reason
                            + "；请设置 "
                            + DevModelSettings.ENV_MODEL
                            + " 或在模型配置页设置编码面默认连接");
        }
        log.info("[开发域] 编码模型未配置，只读任务使用控制面模型：{}", modelId());
        return sharedModel();
    }

    /** 清空模型与智能体缓存；仅用于测试或应用关闭。 */
    public static void resetRuntimeCache() {
        synchronized (PayrollAgentFactory.class) {
            MODELS.clear();
            AGENTS.clear();
            UsageModelCatalog.clear();
        }
    }

    /** 当前缓存的智能体定义数量，用于启动/切换日志与排查。 */
    public static int cachedAgentCount() {
        return AGENTS.size();
    }

    public static String ollamaBaseUrl() {
        return env("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
    }

    /** 按连接版本缓存模型实例；连接任一字段或凭证版本变化即重建。 */
    private static Model cachedModel(ModelConnection connection, String plane) {
        return cached(connection, plane, false);
    }

    /** {@code spare} 为备用连接模型：不再套故障切换，避免切换链。 */
    private static Model cached(ModelConnection connection, String plane, boolean spare) {
        if (connection == null) {
            throw new ModelConfigException(
                    ModelConfigException.CONNECTION_UNAVAILABLE, plane + "模型连接不可用");
        }
        String key =
                plane
                        + "|"
                        + connection.versionKey(credentialVersion(connection))
                        + (spare ? "|spare" : "");
        synchronized (PayrollAgentFactory.class) {
            Model cached = MODELS.get(key);
            if (cached == null) {
                Model built = buildModel(connection, plane);
                cached = spare ? built : failoverModel(built, connection, plane);
                MODELS.put(key, cached);
                UsageModelCatalog.register(cached, plane, connection.provider());
            }
            return cached;
        }
    }

    private static Model failoverModel(Model primary, ModelConnection connection, String plane) {
        Predicate<ModelConnection> filter = "编码面".equals(plane) ? PayrollAgentFactory::writeCapable : null;
        return new FailoverModel(
                primary,
                plane,
                connection.id(),
                () ->
                        modelRegistry
                                .fallback(connection, filter)
                                .map(backup -> cached(backup, plane, true))
                                .orElse(null));
    }

    /** 凭证版本只进入缓存键，不含密钥；未注入凭证服务时返回空串，保持测试与 CLI 兼容。 */
    private static String credentialVersion(ModelConnection connection) {
        ModelCredentialService credentials = modelCredentials;
        if (credentials == null) {
            return "";
        }
        try {
            return credentials.versionOf(connection);
        } catch (RuntimeException e) {
            log.warn("[Model] 读取凭证版本失败，本次按空版本处理：{}", e.getMessage());
            return "";
        }
    }

    private static Model buildModel(ModelConnection connection, String plane) {
        if (connection.baseUrl() != null && !connection.baseUrl().isBlank()) {
            modelOutboundPolicy.check(URI.create(connection.baseUrl()));
        }
        String apiKey = resolveApiKey(connection);
        log.info(
                "[多智能体] {}模型 connection={}，provider={}，model={}，地址={}",
                plane,
                connection.id(),
                connection.provider(),
                connection.model(),
                connection.baseUrl());
        return switch (connection.provider()) {
            case ModelConnection.PROVIDER_OLLAMA -> buildOllamaModel(connection);
            case ModelConnection.PROVIDER_DASHSCOPE -> buildDashscopeModel(connection, apiKey);
            case ModelConnection.PROVIDER_OPENAI ->
                    new OpenAiCompatibleChatModel(
                            connection.model(),
                            connection.baseUrl(),
                            apiKey,
                            connection.contextWindow(),
                            connection.maxTokens(),
                            connection.temperature(0.3),
                            connection.timeoutSeconds(120),
                            modelOutboundPolicy);
            default ->
                    throw new ModelConfigException(
                            ModelConfigException.CAPABILITY_UNSUPPORTED,
                            "不支持的模型提供方：" + connection.provider());
        };
    }

    private static String resolveApiKey(ModelConnection connection) {
        if (!connection.requiresApiKey()) {
            return "";
        }
        ModelCredentialService credentials = modelCredentials;
        if (credentials != null) {
            return credentials.resolveApiKey(connection);
        }
        String envName = connection.apiKeyEnv();
        if (envName == null || envName.isBlank()) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_MISSING,
                    "连接「" + connection.name() + "」未配置密钥");
        }
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            throw new ModelConfigException(
                    ModelConfigException.CREDENTIAL_MISSING, "环境变量 " + envName + " 未设置");
        }
        return value.trim();
    }

    private static Model buildOllamaModel(ModelConnection connection) {
        String baseUrl =
                connection.baseUrl() == null || connection.baseUrl().isBlank()
                        ? ollamaBaseUrl()
                        : connection.baseUrl();
        return buildOllamaModel(
                connection.model(),
                baseUrl,
                connection.numCtx(DEFAULT_OLLAMA_NUM_CTX),
                connection.numPredict(DEFAULT_OLLAMA_NUM_PREDICT),
                connection.temperature(DEFAULT_OLLAMA_TEMPERATURE));
    }

    /** DashScope 走框架扩展：连接上的密钥与地址通过 ModelCreationContext 传入。 */
    private static Model buildDashscopeModel(ModelConnection connection, String apiKey) {
        String id =
                connection.model().startsWith("dashscope:")
                        ? connection.model()
                        : "dashscope:" + connection.model();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey(apiKey).baseUrl(connection.baseUrl()).build();
        return ModelRegistry.resolve(id, context);
    }

    /** 按给定窗口与生成参数构建 Ollama 模型：控制面用环境变量，编码面用 {@code PAYROLL_DEV_*}。 */
    private static Model buildOllamaModel(
            String modelName, String baseUrl, int numCtx, int numPredict, double temperature) {
        if (modelName.isBlank()) {
            throw new PayrollException(
                    "PAYROLL_MODEL 格式错误，应为 ollama:<模型名>，例如 ollama:qwen2.5:3b-instruct");
        }
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
                        .numCtx(numCtx)
                        // 限制单轮生成长度，避免小模型重复输出直到阶段超时。
                        .numPredict(numPredict)
                        .temperature(temperature);
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
