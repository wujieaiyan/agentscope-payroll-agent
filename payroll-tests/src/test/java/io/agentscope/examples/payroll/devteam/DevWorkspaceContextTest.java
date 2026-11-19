package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 工作区上下文：按角色范围列顶层结构、注入关键说明并排除黑名单目录。 */
class DevWorkspaceContextTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("架构师看到仓库结构、README 与角色范围，黑名单目录和密钥文件不出现")
    void rendersTreeAndContextFiles() throws IOException {
        Files.writeString(root.resolve("README.md"), "# 薪酬平台\n\n构建：mvn test", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("payroll-agent"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve(".env"), "SECRET=1", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("id_rsa"), "private", StandardCharsets.UTF_8);

        String context = DevWorkspaceContext.render(DevPathPolicy.ROLE_ARCHITECT, root);

        assertTrue(context.contains("仓库根："));
        assertTrue(context.contains(DevPathPolicy.ROLE_ARCHITECT));
        assertTrue(context.contains("仓库内（黑名单除外）"));
        assertTrue(context.contains("- payroll-agent/"));
        assertTrue(context.contains("- README.md"));
        assertTrue(context.contains("构建：mvn test"));
        assertFalse(context.contains("data/"));
        assertFalse(context.contains(".env"));
        assertFalse(context.contains("id_rsa"));
    }

    @Test
    @DisplayName("前端角色只看得到自己的读范围，根目录 README 不注入")
    void respectsRoleReadScope() throws IOException {
        Files.writeString(root.resolve("README.md"), "整体说明", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("frontend/src"));
        Files.createDirectories(root.resolve("payroll-agent"));

        String context = DevWorkspaceContext.render(DevPathPolicy.ROLE_FRONTEND, root);

        assertTrue(context.contains("frontend/"));
        assertFalse(context.contains("payroll-agent"));
        assertFalse(context.contains("整体说明"));
    }

    @Test
    @DisplayName("上下文超过上限时截断并保留提示标记")
    void truncatesLongContext() {
        String context = DevWorkspaceContext.truncate("行".repeat(DevWorkspaceContext.MAX_CHARS + 1));

        assertTrue(context.length() <= DevWorkspaceContext.MAX_CHARS + 40);
        assertTrue(context.contains("工作区上下文已截断"));
    }

    @Test
    @DisplayName("缺少仓库根时返回空串，不抛异常")
    void emptyWhenRootMissing() {
        assertTrue(DevWorkspaceContext.render(DevPathPolicy.ROLE_ARCHITECT, null).isEmpty());
    }
}
