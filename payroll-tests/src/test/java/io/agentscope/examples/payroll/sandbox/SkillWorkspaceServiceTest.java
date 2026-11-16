package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.agentscope.payroll.sandbox.SandboxSettings;
import io.agentscope.payroll.sandbox.SkillInitRequest;
import io.agentscope.payroll.sandbox.SkillInitRunner;
import io.agentscope.payroll.sandbox.SkillInitState;
import io.agentscope.payroll.sandbox.SkillInitStatus;
import io.agentscope.payroll.sandbox.SkillWorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillWorkspaceServiceTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("初始化成功后只保留 data 与状态文件")
    void keepsOnlyDataAfterReady() throws Exception {
        AtomicReference<Path> packageDir = new AtomicReference<>();
        AtomicReference<Long> timeout = new AtomicReference<>();
        AtomicReference<Long> maxBytes = new AtomicReference<>();
        AtomicReference<String> skillMd = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> setup = new AtomicReference<>();
        SkillInitRunner runner =
                (setupPath, pkg, data, limitMs, limitBytes) -> {
                    packageDir.set(pkg);
                    timeout.set(limitMs);
                    maxBytes.set(limitBytes);
                    try {
                        skillMd.set(Files.readString(pkg.resolve("SKILL.md")));
                        query.set(Files.readString(pkg.resolve("scripts/query.py")));
                        setup.set(Files.readString(pkg.resolve("setup.sh")));
                        Files.writeString(data.resolve("poems.txt"), "静夜思");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return new SkillInitRunner.Outcome(0, false, false, 7, "cloned ok", null);
                };
        SkillWorkspaceService service = service(runner, 2);

        SkillInitStatus started = service.start(request("echo hi"));
        SkillInitStatus done = await(service, "poetry");

        assertEquals(SkillInitStatus.INITIALIZING, started.status());
        assertEquals(SkillInitStatus.READY, done.status());
        assertEquals(0, done.exitCode());
        assertEquals(7, done.sizeBytes());
        assertTrue(done.logTail().contains("cloned ok"));
        assertEquals("# Poetry", skillMd.get());
        assertEquals("print(1)", query.get());
        assertEquals("echo hi", setup.get());
        assertEquals(120_000L, timeout.get());
        assertEquals(1024L, maxBytes.get());
        assertFalse(Files.exists(packageDir.get()), "初始化结束后应删除临时包副本");
        Path data = temp.resolve("skills/poetry/data/poems.txt");
        assertEquals("静夜思", Files.readString(data));
        String state = Files.readString(temp.resolve("skills/poetry/.init.json"));
        assertTrue(state.contains("\"skill_id\":\"poetry\""), state);
        assertTrue(state.contains("\"status\":\"READY\""), state);
    }

    @Test
    @DisplayName("非零退出码映射为 FAILED 并保留日志与退出码")
    void mapsFailureExitCode() throws Exception {
        SkillInitRunner runner =
                (setup, pkg, data, limit, max) ->
                        new SkillInitRunner.Outcome(2, false, false, 0, "git clone failed", null);
        SkillWorkspaceService service = service(runner, 2);

        service.start(request("git clone x"));

        SkillInitStatus done = await(service, "poetry");
        assertEquals(SkillInitStatus.FAILED, done.status());
        assertEquals(2, done.exitCode());
        assertTrue(done.logTail().contains("git clone failed"));
        assertTrue(done.message().contains("退出码 2"), done.message());
    }

    @Test
    @DisplayName("超过数据上限与超时都映射为 FAILED")
    void mapsLimitFailures() throws Exception {
        SkillWorkspaceService exceeded =
                service(
                        (setup, pkg, data, limit, max) ->
                                new SkillInitRunner.Outcome(
                                        -1, false, true, 2048, "too big", "超过上限"),
                        2);
        exceeded.start(request("download"));
        SkillInitStatus exceededStatus = await(exceeded, "poetry");
        assertEquals(SkillInitStatus.FAILED, exceededStatus.status());
        assertEquals(2048, exceededStatus.sizeBytes());
        assertEquals("超过上限", exceededStatus.message());

        SkillWorkspaceService timedOut =
                service(
                        (setup, pkg, data, limit, max) ->
                                new SkillInitRunner.Outcome(-1, true, false, 0, "", null),
                        2);
        timedOut.start(request("sleep"));
        SkillInitStatus timeoutStatus = await(timedOut, "poetry");
        assertEquals(SkillInitStatus.FAILED, timeoutStatus.status());
        assertTrue(timeoutStatus.message().contains("超时"), timeoutStatus.message());
    }

    @Test
    @DisplayName("非法请求不启动容器")
    void rejectsInvalidRequests() {
        AtomicBoolean called = new AtomicBoolean();
        SkillWorkspaceService service = service(skipRunner(called), 2);

        SkillInitStatus badSetup =
                service.start(
                        new SkillInitRequest(
                                "poetry", "references/setup.sh", "echo", List.of(), null, null));
        SkillInitStatus emptySetup =
                service.start(new SkillInitRequest("poetry", "setup.sh", "", List.of(), null, null));
        SkillInitStatus badFile =
                service.start(
                        new SkillInitRequest(
                                "poetry",
                                "setup.sh",
                                "echo",
                                List.of(new SkillInitRequest.File("assets/a.png", "x")),
                                null,
                                null));

        assertEquals(SkillInitStatus.INVALID, badSetup.status());
        assertEquals(SkillInitStatus.INVALID, emptySetup.status());
        assertTrue(emptySetup.message().contains("内容为空"), emptySetup.message());
        assertEquals(SkillInitStatus.INVALID, badFile.status());
        assertFalse(called.get());
    }

    @Test
    @DisplayName("未初始化返回 UNKNOWN，磁盘残留 INITIALIZING 标为 FAILED")
    void reportsUnknownAndStaleStates() throws Exception {
        SkillWorkspaceService service = service(skipRunner(new AtomicBoolean()), 2);
        assertEquals(SkillInitStatus.UNKNOWN, service.status("poetry").status());

        Path skillDir = temp.resolve("skills/poetry");
        Files.createDirectories(skillDir);
        SkillInitState.write(
                skillDir,
                new SkillInitStatus(
                        SkillInitStatus.INITIALIZING,
                        "poetry",
                        "setup.sh",
                        null,
                        System.currentTimeMillis() - 700_000,
                        0,
                        0,
                        "old",
                        "初始化进行中"));

        SkillInitStatus stale = service.status("poetry");
        assertEquals(SkillInitStatus.FAILED, stale.status());
        assertTrue(stale.message().contains("中断"), stale.message());
    }

    @Test
    @DisplayName("同技能与全局并发都在提交时拒绝")
    void rejectsConcurrentInitialization() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SkillInitRunner blocking =
                (setup, pkg, data, limit, max) -> {
                    entered.countDown();
                    awaitLatch(release);
                    return new SkillInitRunner.Outcome(0, false, false, 1, "", null);
                };
        SkillWorkspaceService service = service(blocking, 1);

        assertEquals(SkillInitStatus.INITIALIZING, service.start(request("echo")).status());
        entered.await(5, TimeUnit.SECONDS);
        assertEquals(SkillInitStatus.BUSY, service.start(request("echo")).status());

        SkillInitRequest other =
                new SkillInitRequest("poetry-cn", "setup.sh", "echo", List.of(), null, null);
        SkillInitStatus globalBusy = service.start(other);
        assertEquals(SkillInitStatus.BUSY, globalBusy.status());
        assertTrue(globalBusy.message().contains("并发"), globalBusy.message());

        release.countDown();
        assertEquals(SkillInitStatus.READY, await(service, "poetry").status());
    }

    @Test
    @DisplayName("删除工作区回到未初始化，初始化中禁止删除")
    void deletesWorkspace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SkillInitRunner blocking =
                (setup, pkg, data, limit, max) -> {
                    entered.countDown();
                    awaitLatch(release);
                    return new SkillInitRunner.Outcome(0, false, false, 1, "", null);
                };
        SkillWorkspaceService service = service(blocking, 2);
        service.start(request("echo"));
        entered.await(5, TimeUnit.SECONDS);

        assertEquals(SkillInitStatus.BUSY, service.delete("poetry").status());
        release.countDown();
        assertEquals(SkillInitStatus.READY, await(service, "poetry").status());

        SkillInitStatus deleted = service.delete("poetry");
        assertEquals(SkillInitStatus.UNKNOWN, deleted.status());
        assertFalse(Files.exists(temp.resolve("skills/poetry")));
        assertEquals(SkillInitStatus.UNKNOWN, service.status("poetry").status());
    }

    private SkillWorkspaceService service(SkillInitRunner runner, int concurrency) {
        return new SkillWorkspaceService(settings(concurrency), runner);
    }

    private SandboxSettings settings(int concurrency) {
        return new SandboxSettings(
                "token",
                "docker",
                "payroll-sandbox-runtime:local",
                4,
                temp.toString(),
                temp.resolve("skills").toString(),
                "payroll-sandbox-init:local",
                600_000,
                1024L * 1024 * 1024,
                concurrency);
    }

    private static SkillInitRunner skipRunner(AtomicBoolean called) {
        return (setup, pkg, data, limit, max) -> {
            called.set(true);
            return new SkillInitRunner.Outcome(0, false, false, 0, "", null);
        };
    }

    private static SkillInitRequest request(String setupContent) {
        return new SkillInitRequest(
                "poetry",
                "setup.sh",
                setupContent,
                List.of(
                        new SkillInitRequest.File("SKILL.md", "# Poetry"),
                        new SkillInitRequest.File("scripts/query.py", "print(1)")),
                120_000,
                1024L);
    }

    private static SkillInitStatus await(SkillWorkspaceService service, String skillId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            SkillInitStatus status = service.status(skillId);
            assertNotNull(status);
            if (!SkillInitStatus.INITIALIZING.equals(status.status())) {
                return status;
            }
            Thread.sleep(20);
        }
        fail("初始化未在 5 秒内结束");
        return null;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
