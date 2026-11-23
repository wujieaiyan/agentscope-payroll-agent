package io.agentscope.examples.payroll.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.BatchPayrollResult;
import io.agentscope.examples.payroll.domain.CityPolicy;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.EmployeeView;
import io.agentscope.examples.payroll.domain.MonthlyVariablePay;
import io.agentscope.examples.payroll.domain.PayrollAdjustment;
import io.agentscope.examples.payroll.domain.PayrollApproval;
import io.agentscope.examples.payroll.domain.PayrollApprovalEvent;
import io.agentscope.examples.payroll.domain.PayrollEmailReport;
import io.agentscope.examples.payroll.domain.PayrollEmailResult;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PayrollRunDetail;
import io.agentscope.examples.payroll.domain.PayrollRunFailure;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import io.agentscope.examples.payroll.domain.PayrollRunStatus;
import io.agentscope.examples.payroll.domain.PayrollValidation;
import io.agentscope.examples.payroll.domain.PerformanceReview;
import io.agentscope.examples.payroll.domain.ReviewView;
import io.agentscope.examples.payroll.domain.TaxBracket;
import io.agentscope.examples.payroll.domain.TaxComputation;
import io.agentscope.examples.payroll.domain.TaxDetail;
import io.agentscope.examples.payroll.domain.TaxState;
import io.agentscope.examples.payroll.service.ChatSessionManager;
import io.agentscope.examples.payroll.service.InternalAuth;
import io.agentscope.examples.payroll.service.PayrollEngine;
import io.agentscope.examples.payroll.agent.PayrollAgentFactory;
import io.agentscope.examples.payroll.agent.PayrollTools;
import io.agentscope.examples.payroll.service.PayrollChatService;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1 平台 / 场景边界规则。见 docs/scenario-packs.md 与
 * docs/architecture-remediation-spec.md §6。
 *
 * <p>本轮 7 条规则中，R3 / R6 / R7 用 FreezingArchRule 记录存量违规：
 * 基线写入 payroll-tests/archunit_store，新增违规立即失败；
 * 清理存量后需手动更新基线（-Darchunit.freeze.store.default.allowStoreUpdate=true）。
 */
@AnalyzeClasses(
        packages = {
            "io.agentscope.examples.payroll",
            "io.agentscope.payroll.runtime",
            "io.agentscope.payroll.sandbox"
        },
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String[] PLATFORM_PACKAGES = {
        "io.agentscope.examples.payroll.skill..",
        "io.agentscope.examples.payroll.model..",
        "io.agentscope.examples.payroll.usage..",
        "io.agentscope.examples.payroll.memory..",
        "io.agentscope.examples.payroll.profile..",
        "io.agentscope.examples.payroll.team..",
        "io.agentscope.examples.payroll.kb..",
        "io.agentscope.examples.payroll.health..",
        "io.agentscope.examples.payroll.support.."
    };

    private static final DescribedPredicate<JavaClass> PAYROLL_BUSINESS_TYPES =
            belongToAnyOf(
                    Employee.class,
                    EmployeeView.class,
                    PayrollPolicy.class,
                    CityPolicy.class,
                    TaxBracket.class,
                    TaxComputation.class,
                    TaxDetail.class,
                    TaxState.class,
                    PayrollResult.class,
                    PayrollRun.class,
                    PayrollRunDetail.class,
                    PayrollRunItem.class,
                    PayrollRunStatus.class,
                    PayrollRunFailure.class,
                    PayrollValidation.class,
                    PayrollAdjustment.class,
                    MonthlyVariablePay.class,
                    BatchPayrollResult.class,
                    PerformanceReview.class,
                    ReviewView.class,
                    AttendanceSummary.class,
                    PayrollEmailReport.class,
                    PayrollEmailResult.class,
                    PayrollApproval.class,
                    PayrollApprovalEvent.class);

    private static final DescribedPredicate<JavaClass> SCENARIO_ANCHORS =
            belongToAnyOf(PayrollEngine.class, PayrollTools.class, PayrollAgentFactory.class);

    @ArchTest
    static final ArchRule R1_domain_must_not_depend_on_outer_layers =
            noClasses()
                    .that()
                    .resideInAPackage("io.agentscope.examples.payroll.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "io.agentscope.examples.payroll.service..",
                            "io.agentscope.examples.payroll.web..",
                            "io.agentscope.examples.payroll.skill..",
                            "io.agentscope.examples.payroll.db..")
                    .because("领域模型保持纯模型，不反向依赖服务、入口、技能与存储实现");

    @ArchTest
    static final ArchRule R2_kb_must_not_depend_on_chat_session_layer =
            noClasses()
                    .that()
                    .resideInAPackage("..kb..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(ChatSessionManager.class.getName())
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(PayrollChatService.class.getName())
                    .because("知识库是独立能力，不能反向依赖对话会话与编排实现");

    @ArchTest
    static final ArchRule R3_runtime_must_not_depend_on_web_layer =
            FreezingArchRule.freeze(
                    noClasses()
                            .that()
                            .resideInAPackage("io.agentscope.payroll.runtime..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("io.agentscope.examples.payroll.web..")
                            .because(
                                    "runtime 不依赖控制面 web 实现；存量共享类型（ChatSse 等）冻结后逐步下沉"));

    @ArchTest
    static final ArchRule R4_controllers_only_in_web_or_kb =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("Controller")
                    .and()
                    .resideInAPackage("io.agentscope.examples.payroll..")
                    .should()
                    .resideInAnyPackage(
                            "io.agentscope.examples.payroll.web..",
                            "io.agentscope.examples.payroll.kb..")
                    .because("管理台入口收敛到 web / kb 包；runtime 与 sandbox 的入口位于独立应用命名空间");

    @ArchTest
    static final ArchRule R5_sandbox_must_not_depend_on_common =
            noClasses()
                    .that()
                    .resideInAPackage("io.agentscope.payroll.sandbox..")
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage("io.agentscope.examples.payroll..")
                                    .and(
                                            not(
                                                    resideInAPackage(
                                                            "io.agentscope.examples.payroll.sandbox..")))
                                    .and(not(belongToAnyOf(InternalAuth.class))))
                    .because("沙箱只共享 payroll-protocol 的协议类型与内部鉴权，不依赖 payroll-common");

    @ArchTest
    static final ArchRule R6_platform_must_not_depend_on_payroll_business =
            FreezingArchRule.freeze(
                    noClasses()
                            .that()
                            .resideInAnyPackage(PLATFORM_PACKAGES)
                            .should()
                            .dependOnClassesThat(PAYROLL_BUSINESS_TYPES.or(SCENARIO_ANCHORS))
                            .because(
                                    "双主线：平台包不得依赖薪酬业务类型与 PayrollEngine / PayrollTools / PayrollAgentFactory"));

    @ArchTest
    static final ArchRule R7a_platform_must_not_depend_on_devteam =
            FreezingArchRule.freeze(
                    noClasses()
                            .that()
                            .resideInAnyPackage(PLATFORM_PACKAGES)
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("io.agentscope.examples.payroll.devteam..")
                            .because("devteam 已冻结为 EXPERIMENTAL 场景包，平台包不得引用"));

    @ArchTest
    static final ArchRule R7b_controllers_must_not_depend_on_devteam =
            noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .and()
                    .resideInAPackage("io.agentscope.examples.payroll..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("io.agentscope.examples.payroll.devteam..")
                    .because("payroll-backend 的 Controller 不得把冻结场景包暴露为产品入口");
}
