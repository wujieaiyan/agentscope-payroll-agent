package io.agentscope.payroll.sandbox;

import java.util.List;

/**
 * 沙箱执行请求；脚本内容随请求传输，沙箱不访问 Nacos 或 Web 接口。
 *
 * <p>{@code requireData} 表示技能依赖初始化数据：沙箱会先核对工作区状态，未 READY 时
 * 直接拒绝执行；技能数据目录存在时总是只读挂载到 {@code /data}。
 */
public record SandboxRequest(
        long userId,
        String skillId,
        String scriptPath,
        List<String> args,
        Integer timeoutMs,
        List<ScriptFile> scripts,
        boolean requireData) {

    public SandboxRequest(
            long userId,
            String skillId,
            String scriptPath,
            List<String> args,
            Integer timeoutMs,
            List<ScriptFile> scripts) {
        this(userId, skillId, scriptPath, args, timeoutMs, scripts, false);
    }

    public record ScriptFile(String path, String content) {
    }
}
