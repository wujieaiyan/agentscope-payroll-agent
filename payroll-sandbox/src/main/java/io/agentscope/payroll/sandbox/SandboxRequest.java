package io.agentscope.payroll.sandbox;

import java.util.List;

/** 沙箱执行请求；脚本内容随请求传输，沙箱不访问 Nacos 或 Web 接口。 */
public record SandboxRequest(
        long userId,
        String skillId,
        String scriptPath,
        List<String> args,
        Integer timeoutMs,
        List<ScriptFile> scripts) {

    public record ScriptFile(String path, String content) {
    }
}
