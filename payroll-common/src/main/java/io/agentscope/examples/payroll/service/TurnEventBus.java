package io.agentscope.examples.payroll.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 轮次事件总线：执行端写事件，任意 Web 节点按序号重放或尾随。
 *
 * <p>事件包含模型增量、工具调用与最终 done；事件日志只保留有限时长，
 * 最终答复始终以 chat_messages 为准。
 */
public interface TurnEventBus {

    long append(String turnId, String event, Map<String, Object> data);

    /** 读取 seq 大于 afterSeq 的事件，最多 max 条。 */
    List<TurnEvent> read(String turnId, long afterSeq, int max);

    /** 阻塞读取；超时返回空列表。 */
    List<TurnEvent> readBlocking(
            String turnId, long afterSeq, Duration block, int max);

    void cancel(String turnId);

    boolean cancelled(String turnId);

    void clear(String turnId);

    /** 单条事件；data 为 JSON 字符串。 */
    record TurnEvent(long seq, String event, String data) {
    }
}
