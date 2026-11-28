package io.agentscope.examples.payroll.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** MyBatis 三层边界在存量代码上的正向校验；负例见 {@link MybatisBoundaryRulesNegativeTest}。 */
@AnalyzeClasses(
        packages = {
            "io.agentscope.examples.payroll",
            "io.agentscope.payroll.runtime",
            "io.agentscope.payroll.sandbox"
        },
        importOptions = ImportOption.DoNotIncludeTests.class)
class MybatisBoundaryRulesTest {

    @ArchTest
    static final ArchRule ibatisOnlyInMapperOrSupport =
            MybatisBoundaryRules.IBATIS_ONLY_IN_MAPPER_OR_SUPPORT;

    @ArchTest
    static final ArchRule mapperMustNotDependOnService =
            MybatisBoundaryRules.MAPPER_MUST_NOT_DEPEND_ON_SERVICE;

    @ArchTest
    static final ArchRule serviceAndWebMustNotDependOnMapper =
            MybatisBoundaryRules.SERVICE_AND_WEB_MUST_NOT_DEPEND_ON_MAPPER;
}