package io.agentscope.examples.payroll.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.agentscope.examples.payroll.db.mapper.fixture.ServiceDependentMapperFixture;
import io.agentscope.examples.payroll.service.fixture.IbatisLeakFixture;
import io.agentscope.examples.payroll.service.fixture.MapperDependentServiceFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 三条边界规则各自的负例：规则必须真的拦得住，而不是恒真。 */
class MybatisBoundaryRulesNegativeTest {

    @Test
    @DisplayName("服务层直接依赖 org.apache.ibatis 会被拦下")
    void ibatisLeakIsRejected() {
        assertRejected(
                MybatisBoundaryRules.IBATIS_ONLY_IN_MAPPER_OR_SUPPORT,
                new ClassFileImporter().importClasses(IbatisLeakFixture.class));
    }

    @Test
    @DisplayName("Mapper 回指 service 会被拦下")
    void serviceDependentMapperIsRejected() {
        assertRejected(
                MybatisBoundaryRules.MAPPER_MUST_NOT_DEPEND_ON_SERVICE,
                new ClassFileImporter().importClasses(ServiceDependentMapperFixture.class));
    }

    @Test
    @DisplayName("service 依赖 Mapper 会被拦下")
    void mapperDependentServiceIsRejected() {
        assertRejected(
                MybatisBoundaryRules.SERVICE_AND_WEB_MUST_NOT_DEPEND_ON_MAPPER,
                new ClassFileImporter().importClasses(MapperDependentServiceFixture.class));
    }

    private static void assertRejected(ArchRule rule, JavaClasses classes) {
        AssertionError error = assertThrows(AssertionError.class, () -> rule.check(classes));
        assertTrue(
                error.getMessage() != null && !error.getMessage().isBlank(),
                "违规信息应指出具体类");
    }
}