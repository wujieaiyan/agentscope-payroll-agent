package io.agentscope.examples.payroll.devteam;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务级护栏命中记录（P0-6/C9）：路径越权、黑名单、只读写入等事件由文件系统层登记，
 * 任务结束由运行器统一判定。命中任一条即按 fail-closed 处理，任务置失败且不自动重试。
 */
public final class DevGuardMonitor {

    public static final int MAX_EVENTS = 20;
    private static final int MAX_REASON_CHARS = 200;

    private final List<String> events = new CopyOnWriteArrayList<>();

    public synchronized void breach(String action, String path, String reason) {
        if (events.size() >= MAX_EVENTS) {
            return;
        }
        String event =
                action + ":" + trim(path) + "（" + trim(reason) + "）";
        events.add(event);
    }

    public boolean breached() {
        return !events.isEmpty();
    }

    public int count() {
        return events.size();
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    /** 供任务失败原因与日志使用的摘要，避免把长路径列表写进 500 字上限的 error 字段。 */
    public String summary() {
        List<String> snapshot = events();
        if (snapshot.isEmpty()) {
            return "";
        }
        String head = String.join("；", snapshot.subList(0, Math.min(3, snapshot.size())));
        return snapshot.size() > 3
                ? head + "；另有 " + (snapshot.size() - 3) + " 次"
                : head;
    }

    private static String trim(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() > MAX_REASON_CHARS
                ? normalized.substring(0, MAX_REASON_CHARS)
                : normalized;
    }
}
