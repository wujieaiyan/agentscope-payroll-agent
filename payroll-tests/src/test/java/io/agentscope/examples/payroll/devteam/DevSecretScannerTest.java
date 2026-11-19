package io.agentscope.examples.payroll.devteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevSecretScannerTest {

    @Test
    @DisplayName("新增行中的私钥、云密钥与平台 token 全部命中")
    void detectsHighConfidenceSecrets() {
        String diff =
                """
                diff --git a/backend/config.js b/backend/config.js
                --- /dev/null
                +++ b/backend/config.js
                +const key = "AKIAABCDEFGHIJKLMNOP";
                +const token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";
                +const slack = "xoxb-1234567890-abcdefghij";
                +const openai = "sk-1234567890abcdefghijklmnopqrstuv";
                +const pem = "-----BEGIN RSA PRIVATE KEY-----";
                """;

        List<DevSecretScanner.Finding> findings = DevSecretScanner.scan(diff);

        assertEquals(5, findings.size(), findings.toString());
        assertTrue(findings.stream().allMatch(finding -> finding.path().equals("backend/config.js")));
        assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("AWS 访问密钥")));
        assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("私钥块")));
    }

    @Test
    @DisplayName("明文字口令赋值命中，占位符与删除行不命中")
    void detectsLiteralPasswordButSkipsPlaceholders() {
        String diff =
                """
                diff --git a/backend/db.js b/backend/db.js
                --- a/backend/db.js
                +++ b/backend/db.js
                -const password = "old-super-secret-123456";
                +const password = "P@ssw0rd-prod-987654321";
                +const apiKey = "your_api_key_here_000000";
                +const secret = "${DB_SECRET}";
                +const token = process.env.DB_TOKEN;
                """;

        List<DevSecretScanner.Finding> findings = DevSecretScanner.scan(diff);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals("明文口令/密钥赋值", findings.get(0).kind());
        assertEquals(1, findings.get(0).line());
    }

    @Test
    @DisplayName("普通代码与空 diff 不误报")
    void ignoresOrdinaryCode() {
        assertTrue(DevSecretScanner.scan("").isEmpty());
        assertTrue(DevSecretScanner.scan(null).isEmpty());

        String diff =
                """
                diff --git a/backend/a.js b/backend/a.js
                +++ b/backend/a.js
                +function total(items) { return items.reduce((a, b) => a + b.price, 0); }
                +const header = "Authorization";
                """;
        assertTrue(DevSecretScanner.scan(diff).isEmpty());
    }
}
