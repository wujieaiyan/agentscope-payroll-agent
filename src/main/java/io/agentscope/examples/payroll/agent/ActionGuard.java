package io.agentscope.examples.payroll.agent;

import io.agentscope.examples.payroll.domain.ActionProposal;
import io.agentscope.examples.payroll.service.ChatStreamListener;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单轮对话的写操作闸门：第一个写工具转为待确认提案，之后同轮工具调用一律拒绝。
 *
 * <p>闸门挂在 {@code RuntimeContext} 上，工具按调用取用；智能体实例可跨会话共享，
 * 因此这里不持有任何会话外部状态，只服务本轮。
 */
public final class ActionGuard {

    private static final Logger log = LoggerFactory.getLogger(ActionGuard.class);
    private static final int MAX_TEXT_CHARS = 8_000;

    private final long userId;
    private final long sessionId;
    private final String agentPath;
    private final boolean enabled;
    private final int ttlMinutes;
    private final ToolSafety safety;
    private final ChatStreamListener listener;
    private final StringBuilder textBeforeProposal = new StringBuilder();
    private final List<ActionProposal> proposals = new ArrayList<>();
    private ActionProposal first;

    public ActionGuard(
            long userId,
            long sessionId,
            String agentPath,
            boolean enabled,
            int ttlMinutes,
            ToolSafety safety,
            ChatStreamListener listener) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentPath = agentPath == null ? "" : agentPath;
        this.enabled = enabled;
        this.ttlMinutes = Math.max(1, ttlMinutes);
        this.safety = safety == null ? ToolSafety.defaults() : safety;
        this.listener = listener == null ? ChatStreamListener.NOOP : listener;
    }

    /**
     * 写工具调用入口。返回 null 表示放行；否则返回给模型的 PENDING_CONFIRM 结果。
     */
    public synchronized String intercept(
            String toolName,
            Map<String, Object> args,
            boolean mcp,
            boolean declaredReadOnly,
            boolean destructiveHint) {
        ToolSafety.Decision decision =
                safety.classify(toolName, mcp, declaredReadOnly, destructiveHint, args);
        if (decision.readOnly()) {
            return null;
        }
        if (!enabled) {
            log.warn(
                    "[写操作] 确认开关关闭，按旧行为直接执行：tool={}，风险={}，来源={}",
                    toolName,
                    decision.riskLevel(),
                    decision.source());
            return null;
        }
        if (first != null) {
            log.warn(
                    "[写操作] 本轮已生成提案，拒绝后续工具调用：tool={}，proposalId={}",
                    toolName,
                    first.proposalId());
            return pendingJson(first);
        }
        ActionProposal proposal = draft(toolName, args, decision);
        first = proposal;
        proposals.add(proposal);
        log.info(
                "[写操作] 已生成待确认提案：proposalId={}，tool={}，风险={}，来源={}，sessionId={}",
                proposal.proposalId(),
                proposal.toolName(),
                proposal.riskLevel(),
                proposal.safetySource(),
                sessionId);
        listener.onActionProposal(proposal);
        return pendingJson(proposal);
    }

    public synchronized boolean hasProposal() {
        return first != null;
    }

    public synchronized ActionProposal firstProposal() {
        return first;
    }

    public synchronized List<ActionProposal> proposals() {
        return List.copyOf(proposals);
    }

    /** 记录提案前的已流式正文；提案生成后不再追加，避免模型自述“已完成”。 */
    public synchronized void observeText(String delta) {
        if (first != null || delta == null || delta.isEmpty()) {
            return;
        }
        if (textBeforeProposal.length() >= MAX_TEXT_CHARS) {
            return;
        }
        int room = MAX_TEXT_CHARS - textBeforeProposal.length();
        textBeforeProposal.append(delta, 0, Math.min(room, delta.length()));
    }

    public synchronized String textBeforeProposal() {
        return textBeforeProposal.toString().trim();
    }

    /** 服务端收尾文案；不依赖模型续写，避免卡片之后继续声称已执行。 */
    public synchronized String finalReplyTemplate() {
        if (first == null) {
            return "";
        }
        String impact =
                switch (first.riskLevel()) {
                    case ActionProposal.RISK_AMOUNT -> "涉及金额";
                    case ActionProposal.RISK_IRREVERSIBLE -> "不可撤销";
                    default -> "将修改数据";
                };
        return "已生成待确认操作："
                + first.summary()
                + "（影响："
                + impact
                + "）。请在下方卡片确认后执行，未确认前不会做任何改动。";
    }

    private ActionProposal draft(
            String toolName, Map<String, Object> args, ToolSafety.Decision decision) {
        String proposalId = UUID.randomUUID().toString();
        Map<String, Object> params = args == null ? Map.of() : new LinkedHashMap<>(args);
        String paramsJson = JsonSupport.toJson(params);
        LocalDateTime now = LocalDateTime.now();
        return new ActionProposal(
                proposalId,
                userId,
                sessionId,
                0L,
                agentPath,
                toolName,
                paramsJson,
                summarize(toolName, params),
                decision.riskLevel(),
                decision.source(),
                ActionProposal.STATUS_PENDING,
                proposalId,
                sha256(toolName + "|" + paramsJson),
                now,
                now.plusMinutes(ttlMinutes),
                0L,
                null,
                null,
                null,
                null);
    }

    private static String summarize(String toolName, Map<String, Object> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.length() > 40) {
                text = text.substring(0, 40) + "…";
            }
            pairs.add(entry.getKey() + "=" + text);
            if (pairs.size() >= 3) {
                break;
            }
        }
        return pairs.isEmpty()
                ? "执行工具 " + toolName
                : "执行工具 " + toolName + "（" + String.join("，", pairs) + "）";
    }

    private static String pendingJson(ActionProposal proposal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "PENDING_CONFIRM");
        payload.put("proposal_id", proposal.proposalId());
        payload.put("summary", proposal.summary());
        payload.put("risk_level", proposal.riskLevel());
        payload.put(
                "params",
                JsonSupport.fromJson(
                        proposal.paramsJson(), new TypeReference<Map<String, Object>>() {}));
        payload.put("message", "该操作需要用户在会话内确认，未确认前不会执行。");
        return JsonSupport.toJson(payload);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
