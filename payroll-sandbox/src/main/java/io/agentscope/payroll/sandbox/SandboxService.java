package io.agentscope.payroll.sandbox;

import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** 校验、并发限流与执行调度；全局 4 并发、同一用户 1 并发。 */
@Service
public class SandboxService {

    private final SandboxSettings settings;
    private final SandboxRunner runner;
    private final Semaphore globalSlots;
    private final ConcurrentHashMap<Long, Semaphore> userSlots = new ConcurrentHashMap<>();

    public SandboxService(SandboxSettings settings, SandboxRunner runner) {
        this.settings = settings;
        this.runner = runner;
        this.globalSlots = new Semaphore(settings.maxConcurrency(), true);
    }

    public SandboxResult execute(SandboxRequest request) {
        if (!settings.configured()) {
            return SandboxResult.unavailable("沙箱服务未配置内部令牌");
        }
        if (request == null) {
            return SandboxResult.invalid("请求体为空");
        }
        String scriptSha256;
        try {
            scriptSha256 = validate(request);
        } catch (IllegalArgumentException e) {
            return SandboxResult.invalid(e.getMessage());
        }
        if (request.requireData()) {
            SandboxResult notReady = requireReadyData(request);
            if (notReady != null) {
                return notReady;
            }
        }
        if (!globalSlots.tryAcquire()) {
            return SandboxResult.busy("沙箱并发已满，请稍后重试");
        }
        boolean userAcquired = false;
        try {
            Semaphore userSlot =
                    userSlots.computeIfAbsent(request.userId(), key -> new Semaphore(1, true));
            userAcquired = userSlot.tryAcquire();
            if (!userAcquired) {
                return SandboxResult.busy("同一用户已有脚本在执行，请等待完成");
            }
            return runner.run(request, scriptSha256);
        } finally {
            if (userAcquired) {
                userSlots.get(request.userId()).release();
            }
            globalSlots.release();
        }
    }

    /** 技能声明了 setup：数据必须 READY，否则返回可直接展示给用户的提示；就绪返回 null。 */
    private SandboxResult requireReadyData(SandboxRequest request) {
        SkillInitStatus state = SkillInitState.read(settings.skillDir(request.skillId()));
        if (state != null && state.ready()) {
            return null;
        }
        if (state == null) {
            return SandboxResult.dataNotReady("技能数据未初始化，请先在技能管理台执行初始化");
        }
        if (SkillInitStatus.INITIALIZING.equals(state.status())) {
            return SandboxResult.dataNotReady("技能数据正在初始化，请稍后重试");
        }
        String detail = state.message() == null || state.message().isBlank() ? "" : "：" + state.message();
        return SandboxResult.dataNotReady("技能数据初始化失败，请先在技能管理台重新初始化" + detail);
    }

    /** 返回目标脚本完整 SHA-256；同时完成路径、数量、大小与参数校验。 */
    private static String validate(SandboxRequest request) {
        SandboxProtocol.requireSkillId(request.skillId());
        String target = SandboxProtocol.requireScriptPath(request.scriptPath());
        SandboxProtocol.requireArgs(request.args());
        SandboxProtocol.requireTimeout(request.timeoutMs() == null ? 0 : request.timeoutMs());
        List<SandboxRequest.ScriptFile> scripts = request.scripts();
        if (scripts == null || scripts.isEmpty()) {
            throw new IllegalArgumentException("请求缺少脚本包");
        }
        if (scripts.size() > SandboxProtocol.MAX_SCRIPTS) {
            throw new IllegalArgumentException("脚本文件最多 " + SandboxProtocol.MAX_SCRIPTS + " 个");
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        String targetContent = null;
        for (SandboxRequest.ScriptFile script : scripts) {
            if (script == null) {
                throw new IllegalArgumentException("脚本项不能为空");
            }
            String path = SandboxProtocol.requireScriptPath(script.path());
            if (!seen.add(path)) {
                throw new IllegalArgumentException("脚本路径重复：" + path);
            }
            byte[] bytes =
                    (script.content() == null ? "" : script.content())
                            .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > SandboxProtocol.MAX_SCRIPT_BYTES) {
                throw new IllegalArgumentException(
                        "单个脚本不能超过 " + SandboxProtocol.MAX_SCRIPT_BYTES + " 字节：" + path);
            }
            total += bytes.length;
            if (total > SandboxProtocol.MAX_SCRIPTS_BYTES) {
                throw new IllegalArgumentException(
                        "脚本总量不能超过 " + SandboxProtocol.MAX_SCRIPTS_BYTES + " 字节");
            }
            if (path.equals(target)) {
                targetContent = script.content() == null ? "" : script.content();
            }
        }
        if (targetContent == null) {
            throw new IllegalArgumentException("脚本包中缺少要执行的脚本：" + target);
        }
        return SandboxProtocol.scriptSha256(targetContent);
    }
}
