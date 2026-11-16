package io.agentscope.examples.payroll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.skill.SkillFilePackage;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读技能文件工具：模型只能读取本智能体已绑定技能中的 {@code references/**} 与
 * {@code scripts/**}，资源来自 {@link AgentSkill#getResources()}（内存，不落盘）。
 *
 * <p>单次最多返回 8KB，单轮读取次数由 {@link SkillReadBudget} 控制；不采集引用，
 * 制度原文仍应使用 {@code kb_search}。
 */
public final class SkillReadTools {

    public static final String SKILL_READ = "skill_read";
    public static final int MAX_CONTENT_BYTES = 8 * 1024;

    private static final Logger log = LoggerFactory.getLogger(SkillReadTools.class);

    private final List<AgentSkill> skills;

    public SkillReadTools(List<AgentSkill> skills) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public static boolean hasResources(List<AgentSkill> skills) {
        return skills != null
                && skills.stream()
                        .anyMatch(
                                skill ->
                                        skill != null
                                                && skill.getResourcePaths().stream()
                                                        .anyMatch(SkillFilePackage::isReadable));
    }

    /** 含参考文件时追加的固定提示词；没有文件时返回空串。 */
    public static String guidance(List<AgentSkill> skills) {
        List<String> paths = new ArrayList<>();
        if (skills != null) {
            for (AgentSkill skill : skills) {
                if (skill == null) {
                    continue;
                }
                skill.getResourcePaths().stream()
                        .filter(SkillFilePackage::isReadable)
                        .sorted()
                        .forEach(paths::add);
            }
        }
        if (paths.isEmpty()) {
            return "";
        }
        return """
                ## 技能参考文件
                本角色技能附带以下文件：%s。
                需要时调用 skill_read(path) 读取，单轮最多 %d 次；返回内容超过 8KB 会截断。
                制度、条款、操作手册等长文档请用 kb_search 检索知识库并注明来源，不要把长原文塞进技能正文。
                """
                .formatted(String.join("、", paths), SkillReadBudget.DEFAULT_MAX_READS);
    }

    @Tool(
            name = SKILL_READ,
            description =
                    "读取本智能体已绑定技能包中的参考文件或脚本示例（references/**、scripts/**）。"
                            + "制度原文、条款与操作手册请用 kb_search 检索知识库。",
            readOnly = true)
    public String read(
            @ToolParam(name = "path", description = "相对路径，如 references/faq.md") String path,
            @ToolParam(
                            name = "skill_id",
                            description = "可选，绑定技能 ID；多个技能含同名路径时必填",
                            required = false)
                    String skillId,
            RuntimeContext context) {
        long startedAt = System.nanoTime();
        String result = doRead(path, skillId, context);
        record(path, skillId, result, context, startedAt);
        return result;
    }

    private String doRead(String path, String skillId, RuntimeContext context) {
        String normalized;
        try {
            normalized = SkillFilePackage.normalizeReadPath(path);
        } catch (PayrollException e) {
            return error(e.getMessage());
        }
        SkillReadBudget budget = context == null ? null : context.get(SkillReadBudget.class);
        if (budget != null && !budget.acquire()) {
            log.info("[技能文件] 本轮读取额度已用尽：limit={}，path={}", budget.limit(), normalized);
            return error(
                    "本轮技能文件读取已达上限（" + budget.limit() + " 次），请基于已读取的内容作答");
        }
        AgentSkill target;
        try {
            target = resolve(normalized, skillId);
        } catch (PayloadException e) {
            return error(e.getMessage());
        }
        if (target == null) {
            return error("文件不存在：" + normalized + "（只能读取本智能体已绑定技能的文件）");
        }
        String content = target.getResource(normalized);
        if (content == null) {
            return error("文件不存在：" + normalized + "（技能 " + target.getName() + "）");
        }
        String limited = truncate(content);
        boolean truncated = limited.length() < content.length();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("skill_id", target.getName());
        response.put("path", normalized);
        response.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        response.put("truncated", truncated);
        response.put("content", truncated ? limited + "\n（内容超过 8KB，已截断）" : limited);
        return JsonSupport.toJson(response);
    }

    /**
     * 未指定技能时，只有唯一匹配才自动选中；多个技能含同名路径必须显式传 skill_id，
     * 避免读取到其他角色的文件。
     */
    private AgentSkill resolve(String path, String skillId) {
        Set<String> ids = new LinkedHashSet<>();
        String requested = skillId == null ? "" : skillId.trim();
        if (!requested.isEmpty()) {
            for (AgentSkill skill : skills) {
                if (skill != null
                        && (requested.equals(skill.getName())
                                || requested.equals(skill.getSkillId()))) {
                    return skill.getResource(path) == null ? null : skill;
                }
            }
            throw new PayloadException("本智能体未绑定技能：" + requested);
        }
        AgentSkill matched = null;
        for (AgentSkill skill : skills) {
            if (skill != null && skill.getResource(path) != null) {
                ids.add(skill.getName());
                matched = skill;
            }
        }
        if (ids.size() > 1) {
            throw new PayloadException("多个技能包含该路径，请传入 skill_id：" + String.join("、", ids));
        }
        return matched;
    }

    private static String truncate(String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length <= MAX_CONTENT_BYTES) {
            return content;
        }
        int low = 0;
        int high = content.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (content.substring(0, mid).getBytes(StandardCharsets.UTF_8).length
                    <= MAX_CONTENT_BYTES) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return content.substring(0, low);
    }

    /** 追溯记录模型实际读到的文件内容，供会话侧展示数据来源。 */
    private void record(
            String path, String skillId, String result, RuntimeContext context, long startedAt) {
        ToolTraceRecorder recorder = context == null ? null : context.get(ToolTraceRecorder.class);
        if (recorder == null) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", path == null ? "" : path);
        if (skillId != null && !skillId.isBlank()) {
            args.put("skill_id", skillId);
        }
        try {
            recorder.record(
                    SKILL_READ,
                    JsonSupport.toJson(args),
                    result,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("[技能文件] 记录追溯失败：path={}，原因={}", path, e.getMessage());
        }
    }

    private static String error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("message", message);
        return JsonSupport.toJson(response);
    }

    /** 内部错误类型，避免用异常消息做分支判断。 */
    private static final class PayloadException extends RuntimeException {
        private PayloadException(String message) {
            super(message);
        }
    }
}
