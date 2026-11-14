package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NacosSkillSettingsTest {

    @Test
    void stripsNacosContextPathAndTrailingSlash() {
        NacosSkillSettings settings = settings("http://127.0.0.1:8848/nacos/");

        assertEquals("http://127.0.0.1:8848", settings.server());
    }

    @Test
    void keepsServerWithoutContextPath() {
        NacosSkillSettings settings = settings("http://nacos.example.com:8848");

        assertEquals("http://nacos.example.com:8848", settings.server());
    }

    @Test
    void stripsContextPathAfterReverseProxyPrefix() {
        NacosSkillSettings settings = settings("https://example.com/nacos-console/nacos");

        assertEquals("https://example.com/nacos-console", settings.server());
    }

    private static NacosSkillSettings settings(String server) {
        return new NacosSkillSettings(
                true,
                server,
                "payroll-agent",
                "",
                "",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(60));
    }
}
