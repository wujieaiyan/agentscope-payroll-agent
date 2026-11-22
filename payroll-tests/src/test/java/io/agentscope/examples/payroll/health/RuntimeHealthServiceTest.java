package io.agentscope.examples.payroll.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import io.agentscope.examples.payroll.skill.NacosSkillSettings;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RuntimeHealthServiceTest {

    @Test
    @DisplayName("组件快照只含状态，未配置的 Nacos/Redis/ES 报 SKIPPED")
    void reportsComponentsWithoutAddresses() {
        PayrollDatabase database = PayrollDatabase.inMemory("health-up-" + System.nanoTime());
        RuntimeHealthService service =
                new RuntimeHealthService(
                        database,
                        new RedisHealthProbe(null, DeploymentMode.SINGLE, null),
                        DeploymentMode.SINGLE,
                        disabledSkills(),
                        VectorMemoryService.disabled(),
                        300);

        RuntimeHealthService.Report report = service.snapshot();

        assertEquals(RuntimeHealthService.UP, report.status());
        assertFalse(report.down());
        assertEquals(
                Map.of("db", "UP", "redis", "SKIPPED", "nacos", "SKIPPED", "es", "SKIPPED"),
                report.components());
        String text = report.components().toString();
        assertFalse(text.contains("jdbc"), text);
        assertFalse(text.contains("http"), text);
        assertFalse(text.contains("sa"), text);
    }

    @Test
    @DisplayName("DB 不可用整体 DOWN，调用方应返回 503")
    void databaseFailureMarksOverallDown() throws Exception {
        // H2 文件库的 close() 是空操作，只能把连接指向不存在的库来模拟不可达。
        PayrollDatabase database = PayrollDatabase.inMemory("health-db-" + System.nanoTime());
        Field url = PayrollDatabase.class.getDeclaredField("url");
        url.setAccessible(true);
        url.set(
                database,
                "jdbc:h2:mem:health-missing-" + System.nanoTime() + ";IFEXISTS=TRUE");
        RuntimeHealthService service =
                new RuntimeHealthService(
                        database,
                        new RedisHealthProbe(null, DeploymentMode.SINGLE, null),
                        DeploymentMode.SINGLE,
                        disabledSkills(),
                        VectorMemoryService.disabled(),
                        300);

        RuntimeHealthService.Report report = service.snapshot();

        assertEquals("DOWN", report.components().get("db"));
        assertTrue(report.down());
        assertEquals(RuntimeHealthService.DOWN, report.status());
    }

    @Test
    @DisplayName("cluster 下 Redis 不可达整体 503，single 下只降级")
    void redisFailureFollowsDeploymentMode() {
        RedisHealthProbe clusterProbe = mockProbe(DeploymentMode.CLUSTER);
        try {
            RuntimeHealthService cluster = service(clusterProbe, DeploymentMode.CLUSTER);
            RuntimeHealthService.Report report = cluster.snapshot();
            assertEquals("DOWN", report.components().get("redis"));
            assertTrue(report.down());
            assertEquals(RuntimeHealthService.DOWN, report.status());
        } finally {
            clusterProbe.close();
        }

        RedisHealthProbe singleProbe = mockProbe(DeploymentMode.SINGLE);
        try {
            RuntimeHealthService single = service(singleProbe, DeploymentMode.SINGLE);
            RuntimeHealthService.Report report = single.snapshot();
            assertEquals("DEGRADED", report.components().get("redis"));
            assertFalse(report.down());
            assertEquals(RuntimeHealthService.DEGRADED, report.status());
        } finally {
            singleProbe.close();
        }
    }

    @Test
    @DisplayName("Nacos 超过新鲜度窗口只报 DEGRADED，不摘流")
    void staleNacosOnlyDegrades() throws Exception {
        NacosSkillRegistry skills = enabledSkills();
        setLastSuccessfulListAt(skills, System.currentTimeMillis() - 600_000L);
        RuntimeHealthService service =
                new RuntimeHealthService(
                        PayrollDatabase.inMemory("health-nacos-" + System.nanoTime()),
                        new RedisHealthProbe(null, DeploymentMode.SINGLE, null),
                        DeploymentMode.SINGLE,
                        skills,
                        VectorMemoryService.disabled(),
                        300);

        RuntimeHealthService.Report report = service.snapshot();

        assertEquals("DOWN", report.components().get("nacos"));
        assertFalse(report.down());
        assertEquals(RuntimeHealthService.DEGRADED, report.status());
    }

    private RuntimeHealthService service(RedisHealthProbe probe, DeploymentMode mode) {
        return new RuntimeHealthService(
                PayrollDatabase.inMemory("health-redis-" + System.nanoTime()),
                probe,
                mode,
                disabledSkills(),
                VectorMemoryService.disabled(),
                300);
    }

    /** Redis 不可达时：mock 模板调用即抛出，探针保持 reachable=false。 */
    private static RedisHealthProbe mockProbe(DeploymentMode mode) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(template.opsForValue())
                .thenThrow(new IllegalStateException("Redis 不可达"));
        return new RedisHealthProbe(template, mode, null);
    }

    private static NacosSkillRegistry disabledSkills() {
        return new NacosSkillRegistry(
                new NacosSkillSettings(
                        false,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60)));
    }

    private static NacosSkillRegistry enabledSkills() {
        return new NacosSkillRegistry(
                new NacosSkillSettings(
                        true,
                        "http://127.0.0.1:8848",
                        "payroll-agent",
                        "",
                        "",
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(60)));
    }

    private static void setLastSuccessfulListAt(NacosSkillRegistry registry, long value)
            throws Exception {
        Field field = NacosSkillRegistry.class.getDeclaredField("lastSuccessfulListAt");
        field.setAccessible(true);
        field.setLong(registry, value);
    }
}
