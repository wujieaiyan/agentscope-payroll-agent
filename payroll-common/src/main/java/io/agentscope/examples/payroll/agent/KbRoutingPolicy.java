package io.agentscope.examples.payroll.agent;

import java.util.List;
import java.util.Locale;

/**
 * L0 确定性知识库路由规则：只决定本轮要不要检索，不改写薪酬链路。
 *
 * <p>规则顺序见 docs/kb-routing-policy-spec.md：薪酬一票终止；行动词只压制追问延续、不终止链路；
 * 显式要求与关键词强制检索；会话绑定只作候选。{@code enabled=false} 时退化为
 * {@link KbChatIntent#matches(String)}，与引入本类之前逐条一致。
 */
public final class KbRoutingPolicy {

    private static final List<String> ACTION_MARKERS =
            List.of(
                    "执行", "开始", "生成", "导出", "取消", "重跑", "提交", "确认", "上传", "下载",
                    "创建", "新建", "删除", "修改", "保存", "导入", "发布", "停止", "重新");

    private static final List<String> EXPLICIT_MARKERS =
            List.of(
                    "按知识库", "根据知识库", "按照知识库", "知识库里", "查知识库", "检索知识库",
                    "按资料", "根据资料", "按照资料", "查资料", "参考资料",
                    "按文档", "根据文档", "按照文档", "查文档", "参考文档",
                    "搜索资料", "搜一下资料");

    private static final List<String> FOLLOW_UP_MARKERS =
            List.of(
                    "再", "继续", "接着", "还有", "它", "其", "这个", "那个", "上述", "上面", "刚才",
                    "该", "前者", "后者");

    /** 短问句若带这些特征，不按追问处理，避免引用后的闲聊或新任务被改道检索。 */
    private static final List<String> STRONG_OTHER_MARKERS =
            List.of(
                    "视频", "写作", "文案", "翻译", "代码", "天气", "笑话", "计算", "导出", "下载",
                    "生成", "作图", "画图", "你好", "您好", "谢谢", "多谢", "好的", "再见", "在吗",
                    "哈哈");

    private KbRoutingPolicy() {
    }

    public enum Decision {
        NONE,
        SKIP_PAYROLL,
        FORCE_EXPLICIT,
        FORCE_FOLLOW_UP,
        FORCE_KEYWORD,
        CANDIDATE_BOUND;

        public boolean forced() {
            return this == FORCE_EXPLICIT || this == FORCE_FOLLOW_UP || this == FORCE_KEYWORD;
        }

        public boolean candidate() {
            return this == CANDIDATE_BOUND;
        }
    }

    public enum Source {
        DISABLED,
        PAYROLL,
        ACTION_GUARD,
        EXPLICIT,
        FOLLOW_UP,
        KEYWORD,
        BOUND,
        DEFAULT
    }

    public record Result(Decision decision, Source source, boolean actionGuard) {

        public boolean forced() {
            return decision.forced();
        }
    }

    public record Options(
            boolean enabled,
            boolean followUp,
            boolean boundCandidate,
            boolean actionGuard,
            int followUpMaxChars) {

        public Options {
            followUpMaxChars = followUpMaxChars <= 0 ? 20 : followUpMaxChars;
        }

        /** 关闭 L0 新规则，行为回到纯关键词。 */
        public static Options disabled() {
            return new Options(false, false, false, false, 20);
        }

        /** 规格默认值：新规则关闭，其余子开关按灰度默认。 */
        public static Options defaults() {
            return new Options(false, true, false, true, 20);
        }
    }

    public static Result decide(String question, boolean bound, boolean lastCited, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        String text = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        if (!opts.enabled()) {
            Decision legacy =
                    KbChatIntent.matches(text) ? Decision.FORCE_KEYWORD : Decision.NONE;
            return new Result(legacy, Source.DISABLED, false);
        }
        if (text.isEmpty()) {
            return new Result(Decision.NONE, Source.DEFAULT, false);
        }
        if (KbChatIntent.isPayroll(text)) {
            return new Result(Decision.SKIP_PAYROLL, Source.PAYROLL, false);
        }
        boolean guard = opts.actionGuard() && containsAny(text, ACTION_MARKERS);
        if (containsAny(text, EXPLICIT_MARKERS)) {
            return new Result(Decision.FORCE_EXPLICIT, Source.EXPLICIT, guard);
        }
        boolean followUp = isFollowUp(text, opts.followUpMaxChars());
        if (opts.followUp() && lastCited && !guard && followUp) {
            return new Result(Decision.FORCE_FOLLOW_UP, Source.FOLLOW_UP, guard);
        }
        if (KbChatIntent.matches(text)) {
            return new Result(Decision.FORCE_KEYWORD, Source.KEYWORD, guard);
        }
        if (opts.boundCandidate() && bound && !guard && followUp) {
            return new Result(Decision.CANDIDATE_BOUND, Source.BOUND, guard);
        }
        return new Result(
                Decision.NONE, guard ? Source.ACTION_GUARD : Source.DEFAULT, guard);
    }

    /** 追问延续或短问句（且无强 OTHER 特征）才视为延续上一轮话题。 */
    private static boolean isFollowUp(String text, int maxChars) {
        if (containsAny(text, FOLLOW_UP_MARKERS)) {
            return true;
        }
        return text.length() <= maxChars && !containsAny(text, STRONG_OTHER_MARKERS);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
