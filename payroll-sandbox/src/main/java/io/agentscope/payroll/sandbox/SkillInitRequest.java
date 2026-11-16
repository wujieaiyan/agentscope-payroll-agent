package io.agentscope.payroll.sandbox;

import java.util.List;

/** 初始化请求：包内容随请求传输，沙箱不访问 Nacos 或 Web 接口。 */
public record SkillInitRequest(
        String skillId,
        String setupPath,
        String setupContent,
        List<File> files,
        Integer timeoutMs,
        Long maxBytes) {

    public record File(String path, String content) {
    }
}
