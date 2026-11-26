package io.agentscope.examples.payroll.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillDataProtocolTest {

    @Test
    @DisplayName("目录与文件路径带默认 query，客户端与沙箱可各自复算出同一字符串")
    void buildsCanonicalPaths() {
        assertEquals(
                "/internal/skills/poetry/data/tree?depth=1&path=",
                SkillDataProtocol.treePath("poetry", null, null));
        assertEquals(
                "/internal/skills/poetry/data/tree?depth=2&path=reports",
                SkillDataProtocol.treePath(" poetry ", " reports ", 2));
        assertEquals(
                "/internal/skills/poetry/data/file?maxBytes=262144&path=reports%2Fa.md",
                SkillDataProtocol.filePath("poetry", "reports/a.md", null));
        assertEquals(
                "/internal/skills/poetry/data/file?maxBytes=1024&path=a.txt",
                SkillDataProtocol.filePath("poetry", "a.txt", 1024));
    }

    @Test
    @DisplayName("query 按 key 排序，空格转 %20，中文与加号转义后仍可复算")
    void canonicalisesQuery() {
        assertEquals(
                "?depth=2&path=a%20b", SkillDataProtocol.canonicalQuery(Map.of("path", "a b", "depth", "2")));
        assertEquals(
                "?path=%E4%B8%AD%E6%96%87%2B%25",
                SkillDataProtocol.canonicalQuery(Map.of("path", "中文+%")));
        assertEquals("?path=", SkillDataProtocol.canonicalQuery(Map.of("path", "")));
        assertEquals(
                "?maxBytes=1&path=x",
                SkillDataProtocol.canonicalQuery(Map.of("path", "x", "maxBytes", "1")));
    }

    @Test
    @DisplayName("深度 1–3、预览 1 字节–1 MiB，越界直接拒绝")
    void validatesLimits() {
        assertEquals(1, SkillDataProtocol.requireDepth(null));
        assertEquals(3, SkillDataProtocol.requireDepth(3));
        assertThrows(IllegalArgumentException.class, () -> SkillDataProtocol.requireDepth(0));
        assertThrows(IllegalArgumentException.class, () -> SkillDataProtocol.requireDepth(4));

        assertEquals(256 * 1024, SkillDataProtocol.requireMaxBytes(null));
        assertEquals(1, SkillDataProtocol.requireMaxBytes(1));
        assertEquals(1024 * 1024, SkillDataProtocol.requireMaxBytes(1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> SkillDataProtocol.requireMaxBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillDataProtocol.requireMaxBytes(1024 * 1024 + 1));
    }

    @Test
    @DisplayName("数据路径允许中文与空格，根目录允许空路径，仍拒绝穿越与保留字符")
    void validatesDataPaths() {
        assertEquals("", SkillDataProtocol.requireDataPath(null, "数据目录"));
        assertEquals("", SkillDataProtocol.requireDataPath("  ", "数据目录"));
        assertEquals("reports/2024", SkillDataProtocol.requireDataPath(" reports/2024 ", "数据目录"));
        assertEquals("报表/2024 年.csv", SkillDataProtocol.requireDataPath("报表/2024 年.csv", "数据文件"));
        assertEquals(
                "a%2Fb%20c.md",
                SkillDataProtocol.filePath("poetry", "a/b c.md", null).split("path=")[1]);
        for (String bad : List.of("../etc", "/abs", "a\\b", "a:b", "a*b", "a/b/../c", "a//b", ".")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SkillDataProtocol.requireDataPath(bad, "数据文件"),
                    bad);
        }
        assertThrows(
                IllegalArgumentException.class, () -> SkillDataProtocol.treePath("Poetry", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillDataProtocol.filePath("poetry", "", null));
    }
}
