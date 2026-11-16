package io.agentscope.examples.payroll.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillInitRequirementsTest {

    @Test
    @DisplayName("声明 setup 命令时只认 setup.sh 与 scripts/ 下的现存文件")
    void resolvesDeclaredSetupWithinPackage() {
        Set<String> files = Set.of("setup.sh", "scripts/fetch.py", "references/faq.md");

        assertEquals("setup.sh", SkillInitRequirements.setupPath("sh setup.sh", files));
        assertEquals("scripts/fetch.py", SkillInitRequirements.setupPath("bash scripts/fetch.py --fast", files));
        assertEquals("setup.sh", SkillInitRequirements.setupPath("sh ./setup.sh", files));
        assertNull(SkillInitRequirements.setupPath("sh scripts/missing.sh", files));
        assertNull(SkillInitRequirements.setupPath("sh data/load.sh", files));
    }

    @Test
    @DisplayName("未声明 setup 时回退根目录 setup.sh")
    void fallsBackToRootSetupScript() {
        assertEquals(
                "setup.sh",
                SkillInitRequirements.setupPath(null, Set.of("setup.sh", "scripts/query.py")));
        assertNull(SkillInitRequirements.setupPath(null, Set.of("scripts/query.py")));
        assertNull(SkillInitRequirements.setupPath(null, null));
    }
}
