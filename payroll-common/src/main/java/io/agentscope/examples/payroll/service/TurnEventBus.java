package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.Citation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轮次事件总线：执行端写事件，任意 Web 节点按序号重放或尾随。
 *
 * <p>事件包含模型增量、工具调用与最终 done；事件日志只保留有限时长，
 * 最终答复始终以 chat_messages 为准。
 */
public interface TurnEventBus {

    /** 事件 schema 版本；新增字段保持向后兼容时再加一。 */
    int VERSION = 2;

    /** 已注册的事件类型；未注册的事件仍会下发，但会记录告警供发布方排查。 */
    Set<String> KNOWN_EVENTS =
            Set.of(
                    "start",
                    "agent_start",
                    "agent_text",
                    "agent_end",
                    "thinking",
                    "phase",
                    "context",
                    "tool_start",
                    "tool_args",
                    "tool_result",
                    "text",
                    "action_proposal",
                    "citation",
                    "done",
                    "error");

    static boolean isKnownEvent(String event) {
        return event != null && KNOWN_EVENTS.contains(event);
    }

    long append(String turnId, String event, Map<String, Object> data);

    /** 读取 seq 大于 afterSeq 的事件，最多 max 条。 */
    List<TurnEvent> read(String turnId, long afterSeq, int max);

    /** 阻塞读取；超时返回空列表。 */
    List<TurnEvent> readBlocking(
            String turnId, long afterSeq, Duration block, int max);

    /** 当前仍可读取的最早事件序号；0 表示没有可用事件（尚未写入或已过期）。 */
    long earliestSeq(String turnId);

    /**
     * 冷恢复快照：截至 seq 已合并的文本、工具、智能体与阶段；null 表示暂无可用快照。
     *
     * <p>快照只是重放加速，缺失或不完整时仍以事件日志与最终落库消息为准。
     */
    default TurnSnapshot snapshot(String turnId) {
        return null;
    }

    void cancel(String turnId);

    boolean cancelled(String turnId);

    /** 轮次结束后主动清理取消标记，避免标记残留到下一轮。 */
    void clearCancel(String turnId);

    void clear(String turnId);

    /** 单条事件；data 为 JSON 字符串，version/timestamp 为兼容性元数据。 */
    record TurnEvent(long seq, String event, String data, int version, long timestamp) {

        public TurnEvent(long seq, String event, String data) {
            this(seq, event, data, VERSION, System.currentTimeMillis());
        }

        public boolean supported() {
            return version <= VERSION;
        }
    }

    /** 轮次快照；text 为已合并的主回答文本，tools/agents 保留首次出现顺序。 */
    record TurnSnapshot(
            long seq,
            String text,
            List<String> tools,
            List<String> agents,
            String phase,
            List<Citation> citations) {

        public TurnSnapshot(
                long seq, String text, List<String> tools, List<String> agents, String phase) {
            this(seq, text, tools, agents, phase, List.of());
        }
    }
}
