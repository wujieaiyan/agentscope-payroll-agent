package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.sandbox.SandboxProtocol;
import io.agentscope.payroll.sandbox.SandboxRequest;
import io.agentscope.payroll.sandbox.SandboxResult;
import io.agentscope.payroll.sandbox.SandboxService;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SkillInitState;
import io.agentscope.payroll.sandbox.SkillInitStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxServiceTest {

    private static final String SCRIPT = "print('ok')";

    @Test
    @DisplayName("未配置令牌时直接不可用")
    void unavailableWithoutToken() {
        SandboxService service =
                new SandboxService(settings("", 4), (request, sha) -> SandboxResult.ok(0, "", "", 0, sha, false));

        assertEquals(SandboxResult.UNAVAILABLE, service.execute(request(7L)).status());
    }

    @Test
    @DisplayName("路径越界与缺失目标脚本都按参数错误返回")
    void rejectsInvalidRequests() {
        AtomicInteger calls = new AtomicInteger();
        SandboxService service =
                new SandboxService(settings("token", 4), (request, sha) -> {
                    calls.incrementAndGet();
                    return SandboxResult.ok(0, "", "", 0, sha, false);
                });

        assertEquals(SandboxResult.INVALID, service.execute(null).status());
        assertEquals(
                SandboxResult.INVALID,
                service.execute(
                                new SandboxRequest(
                                        7L,
                                        "payroll-auditor",
                                        "references/a.md",
                                        List.of(),
                                        1000,
                                        List.of(new SandboxRequest.ScriptFile("references/a.md", "x"))))
                        .status());
        assertEquals(
                SandboxResult.INVALID,
                service.execute(
                                new SandboxRequest(
                                        7L,
                                        "payroll-auditor",
                                        "scripts/a.py",
                                        List.of(),
                                        1000,
                                        List.of(new SandboxRequest.ScriptFile("scripts/b.py", "x"))))
                        .status());
        assertEquals(0, calls.get(), "校验失败不应进入执行器");
    }

    @Test
    @DisplayName("命中目标脚本时返回其 SHA-256")
    void passesTargetHash() {
        SandboxService service =
                new SandboxService(settings("token", 4), (request, sha) -> SandboxResult.ok(0, "", "", 0, sha, false));

        SandboxResult result = service.execute(request(7L));

        assertEquals(SandboxResult.OK, result.status());
        assertEquals(SandboxProtocol.scriptSha256(SCRIPT), result.scriptSha256());
    }

    @Test
    @DisplayName("同一用户并发执行被拒绝，不同用户不受影响")
    void limitsOneExecutionPerUser() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SandboxService service =
                new SandboxService(settings("token", 4), (request, sha) -> {
                    if (request.userId() == 7L) {
                        started.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return SandboxResult.ok(0, "", "", 0, sha, false);
                });

        Thread first = new Thread(() -> service.execute(request(7L)));
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertEquals(SandboxResult.BUSY, service.execute(request(7L)).status());
        assertEquals(SandboxResult.OK, service.execute(request(8L)).status());

        release.countDown();
        first.join(5000);
    }

    @Test
    @DisplayName("全局并发占满时返回繁忙")
    void limitsGlobalConcurrency() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SandboxService service =
                new SandboxService(settings("token", 1), (request, sha) -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return SandboxResult.ok(0, "", "", 0, sha, false);
                });

        Thread first = new Thread(() -> service.execute(request(7L)));
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        SandboxResult busy = service.execute(request(8L));
        assertEquals(SandboxResult.BUSY, busy.status());
        assertTrue(busy.message().contains("并发已满"), busy.message());

        release.countDown();
        first.join(5000);
    }

    @Test
    @DisplayName("声明依赖数据的技能在状态非 READY 时拒绝执行，READY 后放行")
    void requiresReadyData(@TempDir Path skillRoot) throws IOException {
        SandboxSettings settings =
                new SandboxSettings(
                        "token", "docker", "image", 4, "", skillRoot.toString(), "init", 600_000, 1_000_000, 2);
        AtomicInteger calls = new AtomicInteger();
        SandboxService service =
                new SandboxService(settings, (request, sha) -> {
                    calls.incrementAndGet();
                    return SandboxResult.ok(0, "", "", 0, sha, false);
                });
        SandboxRequest request = requiringData();

        SandboxResult missing = service.execute(request);
        assertEquals(SandboxResult.DATA_NOT_READY, missing.status());
        assertTrue(missing.message().contains("未初始化"), missing.message());

        Path skillDir = settings.skillDir("payroll-auditor");
        long now = System.currentTimeMillis();
        SkillInitState.write(
                skillDir, SkillInitStatus.initializing("payroll-auditor", "setup.sh", now));
        assertTrue(service.execute(request).message().contains("正在初始化"), "初始化中应提示稍后重试");

        SkillInitState.write(
                skillDir,
                new SkillInitStatus(
                        SkillInitStatus.FAILED,
                        "payroll-auditor",
                        "setup.sh",
                        1,
                        now,
                        now + 10,
                        0,
                        "",
                        "网络不可用"));
        SandboxResult failed = service.execute(request);
        assertEquals(SandboxResult.DATA_NOT_READY, failed.status());
        assertTrue(failed.message().contains("网络不可用"), failed.message());

        SkillInitState.write(
                skillDir,
                new SkillInitStatus(
                        SkillInitStatus.READY,
                        "payroll-auditor",
                        "setup.sh",
                        0,
                        now,
                        now + 10,
                        1024,
                        "",
                        "初始化完成"));
        assertEquals(SandboxResult.OK, service.execute(request).status());
        assertEquals(1, calls.get(), "只有 READY 后才允许进入执行器");
    }

    @Test
    @DisplayName("未声明依赖数据的技能不检查初始化状态")
    void skipsDataCheckWhenNotRequired() {
        SandboxService service =
                new SandboxService(settings("token", 4), (request, sha) -> SandboxResult.ok(0, "", "", 0, sha, false));

        assertEquals(SandboxResult.OK, service.execute(request(7L)).status());
    }

    private static SandboxSettings settings(String token, int concurrency) {
        return new SandboxSettings(token, "docker", "payroll-sandbox-runtime:local", concurrency, "");
    }

    private static SandboxRequest request(long userId) {
        return new SandboxRequest(
                userId,
                "payroll-auditor",
                "scripts/check.py",
                List.of("--month", "2026-11"),
                1000,
                List.of(new SandboxRequest.ScriptFile("scripts/check.py", SCRIPT)));
    }

    private static SandboxRequest requiringData() {
        return new SandboxRequest(
                7L,
                "payroll-auditor",
                "scripts/check.py",
                List.of(),
                1000,
                List.of(new SandboxRequest.ScriptFile("scripts/check.py", SCRIPT)),
                true);
    }
}
