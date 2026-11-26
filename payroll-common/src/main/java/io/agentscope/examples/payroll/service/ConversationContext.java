package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.ChatContextSnapshot;
import io.agentscope.examples.payroll.domain.ChatMessage;
import io.agentscope.examples.payroll.memory.SensitiveTextSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单个会话的滚动上下文：早前轮次压缩成摘要，最近几轮保留用户问题。
 *
 * <p>上下文只用于解析指代（“他”“上个月”“同一部门”），注入提示词时明确禁止引用历史金额；
 * 所有工资数据仍由计算智能体重新调用工具获取，避免管理台改数后模型复述过期结果。
 *
 * <p>助手答复不进入提示词：本地小模型容易复读历史答复并陷入重复生成，
 * 且答复里往往带金额，既拖慢速度又会污染本轮核算。
 */
final class ConversationContext {

    static final int KEEP_RECENT_TURNS = 4;
    static final int COMPRESS_AFTER_TURNS = 6;
    static final int MAX_CONTEXT_CHARS = 3000;
    private static final int MAX_COMPRESS_BATCH = 12;
    private static final int SUMMARY_LIMIT = 900;
    private static final int QUESTION_LIMIT = 240;
    private static final int ANSWER_LIMIT = 500;
    static final int CONTINUATION_LIMIT = 600;

    record Turn(String question, String answer, long lastMessageId) {

        Turn(String question, String answer) {
            this(question, answer, 0);
        }
    }

    private String summary;
    private int summarizedTurns;
    /** 上下文水位：不大于该 id 的消息已折叠进摘要或按规则省略，之后只按水位往后读取。 */
    private long throughMessageId;
    private final List<Turn> turns = new ArrayList<>();

    ConversationContext(String summary, int summarizedTurns) {
        this(summary, summarizedTurns, 0);
    }

    ConversationContext(String summary, int summarizedTurns, long throughMessageId) {
        this.summary = sanitize(summary == null ? "" : summary.trim());
        this.summarizedTurns = Math.max(0, summarizedTurns);
        this.throughMessageId = Math.max(0, throughMessageId);
    }

    /**
     * 用已保存的摘要和消息记录恢复上下文；只配对“用户 → 助手”的完整轮次。
     *
     * <p>消息已按水位窗口化，这里不再用 {@code summarizedTurns} 截断：摘要一旦领先于窗口长度，
     * 旧逻辑会把最近轮次全部丢掉。{@code summarizedTurns} 只保留用于展示与统计。
     */
    static ConversationContext from(ChatContextSnapshot snapshot, List<ChatMessage> messages) {
        ChatContextSnapshot base = snapshot == null ? ChatContextSnapshot.EMPTY : snapshot;
        ConversationContext context =
                new ConversationContext(
                        base.summary(), base.summarizedTurns(), base.throughMessageId());
        String pendingQuestion = null;
        for (ChatMessage message : messages) {
            if ("USER".equals(message.role())) {
                pendingQuestion = message.content();
            } else if ("ASSISTANT".equals(message.role()) && pendingQuestion != null) {
                context.turns.add(new Turn(pendingQuestion, message.content(), message.id()));
                pendingQuestion = null;
            }
        }
        return context;
    }

    void append(String question, String answer) {
        if (question == null || question.isBlank()) {
            return;
        }
        turns.add(new Turn(question.trim(), answer == null ? "" : answer.trim()));
    }

    /** 把上下文拼到本轮问题前；上下文为空时保持原样，避免小模型被空段落干扰。 */
    String augment(String question) {
        return augment(question, "");
    }

    /** 同时注入向量记忆片段；历史片段只作指代线索，金额仍必须重新计算。 */
    String augment(String question, String memoryBlock) {
        return augment(question, memoryBlock, "");
    }

    /** 在同一位置注入偏好前缀块：偏好 → 摘要/历史 → 历史线索 → 本轮问题。 */
    String augment(String question, String memoryBlock, String prefixBlock) {
        return augment(question, memoryBlock, prefixBlock, "");
    }

    /** 知识库材料块位于历史线索之后、本轮问题之前；为空时输出与旧重载逐字一致。 */
    String augment(String question, String memoryBlock, String prefixBlock, String materialBlock) {
        return augment(question, memoryBlock, prefixBlock, materialBlock, "");
    }

    /** 续写块紧跟历史线索，只用于同一角色延续上一轮答复的语气与内容。 */
    String augment(
            String question,
            String memoryBlock,
            String prefixBlock,
            String materialBlock,
            String continuationBlock) {
        String prefix = prefixBlock == null ? "" : prefixBlock.trim();
        String history = render();
        String continuation = continuationBlock == null ? "" : continuationBlock.trim();
        String memory = memoryBlock == null ? "" : memoryBlock.trim();
        String material = materialBlock == null ? "" : materialBlock.trim();
        if (prefix.isEmpty()
                && history.isBlank()
                && continuation.isEmpty()
                && memory.isEmpty()
                && material.isEmpty()) {
            return question;
        }
        StringBuilder builder = new StringBuilder(prefix);
        appendBlock(builder, history);
        appendBlock(builder, continuation);
        appendBlock(builder, memory);
        appendBlock(builder, material);
        return builder.append("\n\n【本轮问题】\n").append(question).toString();
    }

    /**
     * 上一轮助手答复的续写依据：同一智能体被追问、续写或修改时注入，
     * 让语气与主题延续下去；数值仍必须由工具重新获取。
     */
    static String continuationBlock(String answer) {
        String text = answer == null ? "" : answer.trim();
        if (text.isBlank()) {
            return "";
        }
        return """
                【上一轮助手答复｜仅用于延续语气与内容】
                %s
                保持同一角色与主题继续回答；其中出现的员工、部门、期间可作指代线索，
                金额、税额、考勤与政策必须重新调用工具获取，禁止直接复述其中数值。
                【续写依据结束】"""
                .formatted(truncate(text, CONTINUATION_LIMIT));
    }

    private static void appendBlock(StringBuilder builder, String block) {
        if (block.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(block);
    }

    boolean needsCompression() {
        return turns.size() > COMPRESS_AFTER_TURNS || render().length() > MAX_CONTEXT_CHARS;
    }

    /** 需要折叠的较早轮次；最近 {@link #KEEP_RECENT_TURNS} 轮必须保留原文。 */
    List<Turn> compressionBatch() {
        int count = turns.size() - KEEP_RECENT_TURNS;
        if (count <= 0) {
            return List.of();
        }
        return List.copyOf(turns.subList(0, Math.min(count, MAX_COMPRESS_BATCH)));
    }

    void applySummary(String newSummary, int compressedTurns) {
        if (compressedTurns <= 0) {
            return;
        }
        summary = truncate(sanitize(newSummary == null ? "" : newSummary.trim()), SUMMARY_LIMIT);
        int dropped = Math.min(compressedTurns, turns.size());
        advanceWatermark(dropped);
        summarizedTurns += dropped;
        turns.subList(0, dropped).clear();
    }

    /** 压缩失败时的降级：保留已有摘要，只丢弃最早轮次并记一条省略说明。 */
    void discardOldest(int count, String note) {
        int dropped = Math.min(count, turns.size());
        if (dropped <= 0) {
            return;
        }
        advanceWatermark(dropped);
        turns.subList(0, dropped).clear();
        summarizedTurns += dropped;
        String merged = summary.isBlank() ? note : summary + " " + note;
        summary = truncate(merged == null ? "" : merged.trim(), SUMMARY_LIMIT);
    }

    /** 把水位推进到被折叠/丢弃的最后一条消息；尚未落库的轮次（id 0）不推进。 */
    private void advanceWatermark(int dropped) {
        if (dropped <= 0) {
            return;
        }
        long foldedId = turns.get(dropped - 1).lastMessageId;
        if (foldedId > throughMessageId) {
            throughMessageId = foldedId;
        }
    }

    /** 落库后回填最近一轮的助手消息 id，供下一次压缩推进水位。 */
    void markLastAnswer(long messageId) {
        if (messageId <= 0 || turns.isEmpty()) {
            return;
        }
        int last = turns.size() - 1;
        Turn turn = turns.get(last);
        if (turn.lastMessageId() <= 0) {
            turns.set(last, new Turn(turn.question(), turn.answer(), messageId));
        }
    }

    /**
     * 存量会话（水位为 0）的一次性窗口化：先按旧摘要口径跳过已折叠轮次，再裁剪超出窗口上限的轮次，
     * 水位推进到被跳过或裁掉的最后一条已落库消息，后续即可只按水位读取。
     */
    void backfillLegacyWindow(int maxTurns) {
        int summarized = Math.min(summarizedTurns, turns.size());
        if (summarized > 0) {
            advanceWatermark(summarized);
            turns.subList(0, summarized).clear();
        }
        int excess = turns.size() - Math.max(1, maxTurns);
        if (excess > 0) {
            advanceWatermark(excess);
            turns.subList(0, excess).clear();
        }
    }

    String summary() {
        return summary;
    }

    ChatContextSnapshot snapshot() {
        return new ChatContextSnapshot(summary, summarizedTurns, throughMessageId);
    }

    long throughMessageId() {
        return throughMessageId;
    }

    int summarizedTurns() {
        return summarizedTurns;
    }

    int recentTurnCount() {
        return turns.size();
    }

    /** 最近一轮用户问题；没有保留的原始轮次时返回空串，用于短问的检索补全。 */
    String lastQuestion() {
        return turns.isEmpty() ? "" : turns.get(turns.size() - 1).question();
    }

    /** 最近一轮助手答复；只用于同一角色的续写依据，不进入历史指代段。 */
    String lastAnswer() {
        return turns.isEmpty() ? "" : turns.get(turns.size() - 1).answer();
    }

    boolean isEmpty() {
        return summary.isBlank() && turns.isEmpty();
    }

    String render() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(
                """
                【会话上下文｜仅用于理解指代，不是数据来源】
                历史中的员工、部门和期间可用来确定本轮对象；所有金额、税额、考勤与政策必须重新调用工具获取，禁止引用历史数值。
                较早对话摘要：
                """);
        builder.append(summary.isBlank() ? "无" : summary).append('\n');
        builder.append("最近用户问题（按时间先后）：\n");
        if (turns.isEmpty()) {
            builder.append("无\n");
        } else {
            // 即使摘要暂时落后，也只渲染最近几轮，保证单轮提示词有界。
            int start = Math.max(0, turns.size() - COMPRESS_AFTER_TURNS);
            for (int i = start; i < turns.size(); i++) {
                builder.append("- ")
                        .append(flatten(turns.get(i).question(), QUESTION_LIMIT))
                        .append('\n');
            }
        }
        builder.append("助手的历史答复已省略：所有金额、税额、考勤与政策必须重新调用工具获取。\n");
        builder.append("请只处理【本轮问题】，不要逐条重复历史问答。\n");
        builder.append("【上下文结束】");
        return builder.toString();
    }

    static String summaryPrompt(String previousSummary, List<Turn> batch) {
        String existing =
                previousSummary == null || previousSummary.isBlank()
                        ? "无"
                        : previousSummary.trim();
        String dialogue =
                batch.stream()
                        .map(
                                turn ->
                                        "用户："
                                                + flatten(turn.question(), QUESTION_LIMIT)
                                                + "\n助手："
                                                + flatten(turn.answer(), ANSWER_LIMIT))
                        .collect(Collectors.joining("\n"));
        return """
                【已有摘要】
                %s

                【待压缩对话】
                %s
                """
                .formatted(existing, dialogue);
    }

    private static String flatten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").trim(), limit);
    }

    /** 摘要由小模型生成，必须在代码层兜底删掉含金额或比例的整句，避免模型复述过期数值。 */
    static String sanitize(String summary) {
        return SensitiveTextSanitizer.sanitize(summary);
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
