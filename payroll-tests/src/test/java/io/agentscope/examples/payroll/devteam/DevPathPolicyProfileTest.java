package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.domain.PayrollException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 仓库路径 profile（P0-6）：按仓库覆盖角色范围，黑名单与归一化不可覆盖。 */
class DevPathPolicyProfileTest {

    @TempDir Path temp;

    @AfterEach
    void reset() {
        DevPathPolicy.resetProfile();
    }

    @Test
    @DisplayName("profile 覆盖声明角色的读写范围，未声明角色保持内置 payroll 范围")
    void profileOverridesDeclaredRoles() throws IOException {
        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {"project-implementer": {"read": ["**"], "write": ["backend/**"]}}}
                        """));

        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "backend/src/A.java"));
        assertFalse(
                DevPathPolicy.canWrite(
                        DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"));
        assertTrue(
                DevPathPolicy.canRead(
                        DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend/src/App.vue"));
        assertTrue(
                DevPathPolicy.describeScope(DevPathPolicy.ROLE_IMPLEMENTER).contains("backend/**"));
    }

    @Test
    @DisplayName("空 read 关闭文件系统；缺省 write 视为只读角色")
    void emptyReadAndMissingWrite() throws IOException {
        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {
                          "project-manager": {"read": []},
                          "project-architect": {"read": ["docs/**"]}
                        }}
                        """));

        DevPathPolicy.RolePaths manager = DevPathPolicy.role(DevPathPolicy.ROLE_MANAGER).orElseThrow();
        assertFalse(manager.readEnabled());
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_MANAGER, "docs/readme.md"));
        assertFalse(DevPathPolicy.canList(DevPathPolicy.ROLE_MANAGER, "docs"));

        DevPathPolicy.RolePaths architect =
                DevPathPolicy.role(DevPathPolicy.ROLE_ARCHITECT).orElseThrow();
        assertTrue(architect.readEnabled());
        assertFalse(architect.writable());
        assertTrue(DevPathPolicy.canRead(DevPathPolicy.ROLE_ARCHITECT, "docs/readme.md"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_ARCHITECT, "pom.xml"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_ARCHITECT, "docs/readme.md"));
    }

    @Test
    @DisplayName("黑名单与路径归一化优先于 profile：越权路径仍被拒绝")
    void blacklistCannotBeOverridden() throws IOException {
        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {"project-implementer": {"read": ["**"], "write": ["**"]}}}
                        """));

        assertFalse(
                DevPathPolicy.canRead(DevPathPolicy.ROLE_IMPLEMENTER, ".git/config"));
        assertFalse(
                DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "data/payroll.mv.db"));
        assertFalse(
                DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "deploy/server.key"));
        assertFalse(
                DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "../outside.txt"));
        assertTrue(
                DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "backend\\src\\A.java"));
    }

    @Test
    @DisplayName("resetProfile 与空参数恢复内置 payroll 范围")
    void resetRestoresDefaults() throws IOException {
        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {"project-implementer": {"read": ["**"], "write": ["backend/**"]}}}
                        """));
        assertFalse(
                DevPathPolicy.canWrite(
                        DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"));

        DevPathPolicy.resetProfile();
        assertTrue(
                DevPathPolicy.canWrite(
                        DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"));

        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {"project-implementer": {"read": [], "write": []}}}
                        """));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_IMPLEMENTER, "pom.xml"));
        DevPathPolicy.loadProfile(null);
        assertTrue(DevPathPolicy.canRead(DevPathPolicy.ROLE_IMPLEMENTER, "pom.xml"));
        assertTrue(
                DevPathPolicy.verifyCommands(DevPathPolicy.ROLE_IMPLEMENTER).isEmpty(),
                "重置后不应保留 verify 命令");
    }

    @Test
    @DisplayName("verify 段解析为受限 runner 命令，缺省名称/超时/镜像按默认值处理")
    void verifyCommandsFollowProfile() throws IOException {
        DevPathPolicy.loadProfile(
                profile(
                        """
                        {"roles": {"project-implementer": {"read": ["**"], "write": ["**"]}},
                         "verify": {"project-implementer": [
                           {"name": "backend-build", "command": ["mvn", "-o", "-q", "compile"],
                            "timeoutSeconds": 1200, "image": "maven:3.9-eclipse-temurin-17"},
                           {"command": ["npm", "run", "build"]}
                         ]}}
                        """));

        List<DevVerifyCommand> commands =
                DevPathPolicy.verifyCommands(DevPathPolicy.ROLE_IMPLEMENTER);
        assertEquals(2, commands.size());

        DevVerifyCommand build = commands.get(0);
        assertEquals("backend-build", build.name());
        assertEquals(List.of("mvn", "-o", "-q", "compile"), build.command());
        assertEquals(1200, build.timeoutSeconds());
        assertEquals("maven:3.9-eclipse-temurin-17", build.image());

        DevVerifyCommand npm = commands.get(1);
        assertEquals(DevPathPolicy.ROLE_IMPLEMENTER, npm.name());
        assertEquals(DevVerifyCommand.DEFAULT_TIMEOUT_SECONDS, npm.timeoutSeconds());
        assertTrue(npm.image().isEmpty());
        assertTrue(DevPathPolicy.verifyCommands(DevPathPolicy.ROLE_FRONTEND).isEmpty());
    }

    @Test
    @DisplayName("未知角色、非法 glob 与缺失字段整体拒绝，失败不改变当前范围")
    void rejectsInvalidProfiles() throws IOException {
        assertRejected("{\"roles\": {\"project-unknown\": {\"read\": [\"**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"/abs/**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"../**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"a/../b/**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"C:/repo/**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"~/repo/**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [\"\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"write\": [\"backend/**\"]}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": \"**\"}}}");
        assertRejected("{\"roles\": {\"project-implementer\": {\"read\": [1]}}}");
        assertRejected("{\"roles\": {}}");
        assertRejected("{\"other\": {}}");
        assertRejected("[]");

        String roles = "\"roles\": {\"project-implementer\": {\"read\": [\"**\"]}}";
        assertRejected("{" + roles + ", \"verify\": []}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-unknown\": [{\"command\": [\"mvn\"]}]}}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-implementer\": {\"command\": [\"mvn\"]}}}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-implementer\": [{\"name\": \"x\"}]}}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-implementer\": [{\"command\": []}]}}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-implementer\": [{\"command\": [\"mvn\", 1]}]}}");
        assertRejected(
                "{" + roles + ", \"verify\": {\"project-implementer\": [{\"command\": [\"mvn\", \" \"]}]}}");
        assertRejected(
                "{" + roles
                        + ", \"verify\": {\"project-implementer\": [{\"command\": [\"bash\", \"-c\", \"id\"]}]}}");
        assertRejected(
                "{" + roles
                        + ", \"verify\": {\"project-implementer\": [{\"command\": [\"mvn\"], \"timeoutSeconds\": \"900\"}]}}");
        assertRejected(
                "{" + roles
                        + ", \"verify\": {\"project-implementer\": [{\"command\": [\"mvn\"], \"image\": 5}]}}");

        assertThrows(
                PayrollException.class,
                () -> DevPathPolicy.loadProfile(temp.resolve("missing-profile.json")));
    }

    private void assertRejected(String json) throws IOException {
        Path file = profile(json);
        assertThrows(PayrollException.class, () -> DevPathPolicy.loadProfile(file), json);
        assertTrue(
                DevPathPolicy.canWrite(
                        DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"),
                "失败后应保留内置范围：" + json);
    }

    private Path profile(String json) throws IOException {
        Path file = temp.resolve("paths-" + Math.abs(json.hashCode()) + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
