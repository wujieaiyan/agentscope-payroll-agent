package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevPathPolicyTest {

    @Test
    @DisplayName("后端开发只能写 payroll-*/src/**，其余路径一律拒绝")
    void implementerWriteScope() {
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/java/A.java"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-agent/src/test/java/B.java"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "pom.xml"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "frontend/src/App.vue"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/srcx/A.java"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "docs/readme.md"));
    }

    @Test
    @DisplayName("前端只能写 frontend/src/** 与 frontend/public/**")
    void frontendWriteScope() {
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend/src/views/ChatView.vue"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend/public/logo.svg"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend/package.json"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend/node_modules/a.js"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "payroll-common/src/main/java/A.java"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_FRONTEND, "payroll-common/src/main/java/A.java"));
    }

    @Test
    @DisplayName("数据库只能写迁移脚本与 SQL 脚本目录")
    void dbaWriteScope() {
        assertTrue(
                DevPathPolicy.canWrite(
                        DevPathPolicy.ROLE_DBA,
                        "payroll-common/src/main/resources/db/migration/V12__x.sql"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DBA, "scripts/sql/fix.sql"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DBA, "scripts/run.sh"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DBA, "payroll-common/src/main/java/A.java"));
    }

    @Test
    @DisplayName("运维只能写 docker/**、deploy/**、scripts/** 与 CI 配置")
    void devopsWriteScope() {
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "docker/Dockerfile"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "deploy/compose.yml"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "scripts/deploy.sh"));
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, ".github/workflows/ci.yml"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "pom.xml"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "frontend/src/App.vue"));
    }

    @Test
    @DisplayName("产品经理不授予文件系统，其余只读角色禁止写入")
    void readOnlyRoles() {
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_PRODUCT, "pom.xml"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_PRODUCT, "docs/readme.md"));

        for (String role :
                new String[] {
                    DevPathPolicy.ROLE_ARCHITECT,
                    DevPathPolicy.ROLE_MANAGER,
                    DevPathPolicy.ROLE_QA,
                    DevPathPolicy.ROLE_REVIEWER
                }) {
            assertTrue(DevPathPolicy.canRead(role, "payroll-common/src/main/java/A.java"), role);
            assertFalse(DevPathPolicy.canWrite(role, "payroll-common/src/main/java/A.java"), role);
        }
    }

    @Test
    @DisplayName("黑名单优先：读写都不放行 .git、data 与密钥文件")
    void blacklistWins() {
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, ".git/config"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "scripts/../.git/hooks/pre-commit"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DBA, "data/payroll.mv.db"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, "data/payroll.mv.db"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "docker/.env"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "docker/.env.prod"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "deploy/server.key"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "deploy/tls.pem"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/main/resources/credentials.json"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, "scripts/id_rsa"));
    }

    @Test
    @DisplayName("黑名单补齐云/密钥凭据：pfx/kdbx/ovpn、SSH 私钥与 CI token 文件")
    void blacklistCoversCredentialFiles() {
        for (String path :
                new String[] {
                    "deploy/cert.pfx",
                    "deploy/vault.kdbx",
                    "deploy/client.ovpn",
                    "deploy/id_ed25519",
                    "deploy/id_dsa",
                    "deploy/.npmrc",
                    "deploy/.netrc",
                    "deploy/.pgpass",
                    "deploy/.htpasswd",
                    ".m2/settings.xml",
                    "docker/.m2/settings.xml",
                    "deploy/credentials-prod.json",
                    "deploy/secrets.yaml",
                }) {
            assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, path), path);
            assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_DEVOPS, path), path);
        }
        assertTrue(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, "maven/settings.xml"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, ".ssh/config"));
    }

    @Test
    @DisplayName("绝对路径、盘符、目录穿越与空路径直接拒绝")
    void rejectsEscapingPaths() {
        for (String path :
                new String[] {
                    "/etc/passwd",
                    "C:\\repo\\payroll-common\\src\\A.java",
                    "~/.ssh/config",
                    "../pom.xml",
                    "payroll-common/../../pom.xml",
                    "   ",
                }) {
            assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, path), path);
            assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, path), path);
        }
    }

    @Test
    @DisplayName("控制字符、冒号数据流与超长路径一律拒绝")
    void rejectsUnsafeCharacters() {
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, "docs/a\u0000b.md"));
        assertFalse(DevPathPolicy.canRead(DevPathPolicy.ROLE_REVIEWER, "docs/a\u001fb.md"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/a.java:stream"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common/src/" + "a".repeat(513)));
    }

    @Test
    @DisplayName("反斜杠与 ./ 前缀按仓库相对路径归一化")
    void normalizesWindowsSeparators() {
        assertTrue(DevPathPolicy.canWrite(DevPathPolicy.ROLE_FRONTEND, "frontend\\src\\views\\ChatView.vue"));
        assertTrue(DevPathPolicy.canRead(DevPathPolicy.ROLE_MANAGER, "./payroll-common/./src/A.java"));
        assertFalse(DevPathPolicy.canWrite(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common\\src\\..\\..\\pom.xml"));
    }

    @Test
    @DisplayName("ls 导航：可列出白名单路径的上层目录，黑名单与越权目录不可列出")
    void listScopeAllowsAncestors() {
        assertTrue(DevPathPolicy.canList(DevPathPolicy.ROLE_FRONTEND, "frontend"));
        assertTrue(DevPathPolicy.canList(DevPathPolicy.ROLE_FRONTEND, "frontend/src"));
        assertFalse(DevPathPolicy.canList(DevPathPolicy.ROLE_FRONTEND, "payroll-common"));
        assertTrue(DevPathPolicy.canList(DevPathPolicy.ROLE_IMPLEMENTER, "payroll-common"));
        assertFalse(DevPathPolicy.canList(DevPathPolicy.ROLE_IMPLEMENTER, "data"));
        assertFalse(DevPathPolicy.canList(DevPathPolicy.ROLE_IMPLEMENTER, ".git"));
        assertFalse(DevPathPolicy.canList(DevPathPolicy.ROLE_PRODUCT, "frontend"));
    }

    @Test
    @DisplayName("未知或空角色一律拒绝")
    void rejectsUnknownRoles() {
        assertFalse(DevPathPolicy.canRead("project-hacker", "pom.xml"));
        assertFalse(DevPathPolicy.canRead("", "pom.xml"));
        assertFalse(DevPathPolicy.canRead(null, "pom.xml"));
    }
}
