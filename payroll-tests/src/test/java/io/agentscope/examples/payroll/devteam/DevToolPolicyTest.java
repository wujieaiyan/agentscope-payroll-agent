package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevToolPolicyTest {

    @Test
    @DisplayName("写工具只在可写角色绑定编辑会话时开放")
    void writeToolsRequireSessionAndWritableRole() {
        assertTrue(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "write_file", true));
        assertTrue(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "edit_file", true));
        assertFalse(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "write_file", false));
        assertFalse(
                DevToolPolicy.allowed(DevPathPolicy.ROLE_REVIEWER, "write_file", true));
        assertFalse(
                DevToolPolicy.allowed(DevPathPolicy.ROLE_QA, "edit_file", true));
    }

    @Test
    @DisplayName("只读文件工具按角色读权限开放，产品经理没有文件系统")
    void readToolsFollowPathScope() {
        for (String tool : DevToolPolicy.READ_TOOLS) {
            assertTrue(
                    DevToolPolicy.allowed(DevPathPolicy.ROLE_REVIEWER, tool, false), tool);
            assertTrue(
                    DevToolPolicy.allowed(DevPathPolicy.ROLE_IMPLEMENTER, tool, false), tool);
            assertFalse(DevToolPolicy.allowed(DevPathPolicy.ROLE_PRODUCT, tool, false), tool);
        }
    }

    @Test
    @DisplayName("skill_read 与 kb_search 是只读共享能力，所有已知角色可用")
    void sharedReadToolsAllowed() {
        for (String role :
                new String[] {
                    DevPathPolicy.ROLE_PRODUCT,
                    DevPathPolicy.ROLE_ARCHITECT,
                    DevPathPolicy.ROLE_MANAGER,
                    DevPathPolicy.ROLE_IMPLEMENTER,
                    DevPathPolicy.ROLE_REVIEWER
                }) {
            assertTrue(DevToolPolicy.allowed(role, "skill_read", false), role);
            assertTrue(DevToolPolicy.allowed(role, "kb_search", false), role);
        }
    }

    @Test
    @DisplayName("shell/终端/进程/网络/容器/MCP/Git 写工具一律拒绝，即使角色可写")
    void dangerousToolsAlwaysDenied() {
        for (String tool :
                new String[] {
                    "bash",
                    "shell_execute",
                    "run_terminal",
                    "process_start",
                    "execute_command",
                    "http_request",
                    "web_fetch",
                    "browser_open",
                    "docker_run",
                    "mcp__filesystem__write",
                    "git_push",
                    "npm_publish"
                }) {
            DevToolPolicy.Decision decision =
                    DevToolPolicy.evaluate(DevPathPolicy.ROLE_IMPLEMENTER, tool, true);
            assertFalse(decision.allowed(), tool);
            assertFalse(decision.reason().isBlank(), tool);
        }
    }

    @Test
    @DisplayName("未登记工具与未知角色按 fail-closed 拒绝")
    void unknownToolsAndRolesDenied() {
        assertFalse(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "record_payment", true));
        assertFalse(DevToolPolicy.allowed("project-hacker", "read_file", false));
        assertFalse(DevToolPolicy.allowed(null, "read_file", false));
        assertFalse(DevToolPolicy.allowed(DevPathPolicy.ROLE_IMPLEMENTER, " ", true));
    }

    @Test
    @DisplayName("工具名大小写与空白归一化后仍命中策略")
    void normalizesToolNames() {
        assertTrue(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "  READ_FILE  ", false));
        assertFalse(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, "BASH", true));
        assertFalse(
                DevToolPolicy.allowed(
                        DevPathPolicy.ROLE_IMPLEMENTER, " Edit_File ", false));
    }

    @Test
    @DisplayName("allowedTools 汇总角色可用集合，未知角色为空")
    void summarizesAllowedTools() {
        Set<String> writable =
                DevToolPolicy.allowedTools(DevPathPolicy.ROLE_IMPLEMENTER, true);
        assertTrue(writable.containsAll(DevToolPolicy.READ_TOOLS));
        assertTrue(writable.containsAll(DevToolPolicy.WRITE_TOOLS));
        assertTrue(writable.containsAll(DevToolPolicy.SHARED_READ_TOOLS));

        Set<String> readOnly =
                DevToolPolicy.allowedTools(DevPathPolicy.ROLE_REVIEWER, true);
        assertTrue(readOnly.containsAll(DevToolPolicy.READ_TOOLS));
        assertFalse(readOnly.contains("write_file"));

        Set<String> product =
                DevToolPolicy.allowedTools(DevPathPolicy.ROLE_PRODUCT, false);
        assertTrue(product.equals(DevToolPolicy.SHARED_READ_TOOLS));

        assertTrue(DevToolPolicy.allowedTools("project-unknown", false).isEmpty());
    }
}
