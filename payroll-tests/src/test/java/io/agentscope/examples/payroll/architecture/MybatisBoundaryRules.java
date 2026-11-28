package io.agentscope.examples.payroll.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * MyBatis 边界（规格 §5.1 三条硬边界）：只有 Mapper 与装配支持包能碰 MyBatis；
 * Mapper 不回指服务层；服务/Web 层不得绕过 store 直接依赖 Mapper。
 */
final class MybatisBoundaryRules {

    static final String MAPPER_PACKAGE = "..db.mapper..";
    static final String SUPPORT_PACKAGE = "..db.support..";

    static final ArchRule IBATIS_ONLY_IN_MAPPER_OR_SUPPORT =
            noClasses()
                    .that()
                    .resideInAnyPackage("io.agentscope..")
                    .and()
                    .resideOutsideOfPackages(MAPPER_PACKAGE, SUPPORT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.apache.ibatis..")
                    .because("MyBatis 只能在 db.mapper / db.support 内使用");

    /**
     * P0-6 时 {@code db.mapper} 还没有类，空匹配是预期状态；允许空跑，等 P0-8 落地试点 Mapper 后
     * 这条规则自然开始生效——故意写成 {@code allowEmptyShould} 而不是等有类了再加规则，
     * 免得上线那天忘了补。
     */
    static final ArchRule MAPPER_MUST_NOT_DEPEND_ON_SERVICE =
            noClasses()
                    .that()
                    .resideInAPackage(MAPPER_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..service..")
                    .because("Mapper 只是一条语句，不得回指业务服务")
                    .allowEmptyShould(true);

    static final ArchRule SERVICE_AND_WEB_MUST_NOT_DEPEND_ON_MAPPER =
            noClasses()
                    .that()
                    .resideInAnyPackage("..service..", "..web..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(MAPPER_PACKAGE)
                    .because("应用层只能依赖 store 端口，绕过 store 状态机会破坏事务边界");

    private MybatisBoundaryRules() {
    }
}