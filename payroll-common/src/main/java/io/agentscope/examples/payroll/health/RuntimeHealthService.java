package io.agentscope.examples.payroll.health;

import io.agentscope.examples.payroll.config.DeploymentMode;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.memory.VectorMemoryService;
import io.agentscope.examples.payroll.skill.NacosSkillRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对编排与运维暴露的组件健康快照：{@code db/redis/nacos/es} 四项，不含地址与账号。
 *
 * <p>整体状态跟随 readiness 核心依赖（DB，cluster 下的 Redis）；Nacos 与 ES 只影响
 * 整体 DEGRADED，不会摘流。Nacos 用最近一次成功列表时间判定新鲜度，ES 为可选项。
 */
public final class RuntimeHealthService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeHealthService.class);

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String DEGRADED = "DEGRADED";
    public static final String SKIPPED = "SKIPPED";
    public static final String UNKNOWN = "UNKNOWN";

    private final PayrollDatabase database;
    private final RedisHealthProbe redis;
    private final DeploymentMode mode;
    private final NacosSkillRegistry skills;
    private final VectorMemoryService memory;
    private final long nacosStaleMillis;

    public RuntimeHealthService(
            PayrollDatabase database,
            RedisHealthProbe redis,
            DeploymentMode mode,
            NacosSkillRegistry skills,
            VectorMemoryService memory,
            long nacosStaleSeconds) {
        this.database = database;
        this.redis = redis;
        this.mode = mode == null ? DeploymentMode.SINGLE : mode;
        this.skills = skills;
        this.memory = memory;
        this.nacosStaleMillis = Math.max(1, nacosStaleSeconds) * 1000L;
    }

    /** {@code down} 为 true 时应返回 503；其余降级项只影响 status 文案。 */
    public record Report(String status, Map<String, Object> components, boolean down) {}

    public Report snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> components = new LinkedHashMap<>();

        String db = checkDatabase();
        String redisStatus = redisStatus();
        String nacos = nacosStatus(now);
        String es = memory == null ? SKIPPED : memory.esStatus();
        components.put("db", db);
        components.put("redis", redisStatus);
        components.put("nacos", nacos);
        components.put("es", es);

        boolean down = DOWN.equals(db) || (mode.cluster() && DOWN.equals(redisStatus));
        String status;
        if (down) {
            status = DOWN;
        } else if (hasProblem(redisStatus) || hasProblem(nacos) || DOWN.equals(es)) {
            status = DEGRADED;
        } else {
            status = UP;
        }
        return new Report(status, components, down);
    }

    private String checkDatabase() {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                return result.next() ? UP : DOWN;
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("[健康] DB 探测失败：{}", e.getMessage());
            return DOWN;
        }
    }

    private String redisStatus() {
        if (redis == null) {
            return SKIPPED;
        }
        RedisHealthProbe.Summary summary = redis.summary();
        if (!summary.configured()) {
            return SKIPPED;
        }
        return PayrollReadinessHealthIndicator.redisStatus(summary, mode.cluster());
    }

    private String nacosStatus(long now) {
        if (skills == null || !skills.nacosEnabled()) {
            return SKIPPED;
        }
        long last = skills.lastSuccessfulListAt();
        if (last <= 0) {
            return UNKNOWN;
        }
        return now - last <= nacosStaleMillis ? UP : DOWN;
    }

    private static boolean hasProblem(String status) {
        return DEGRADED.equals(status) || DOWN.equals(status);
    }
}
