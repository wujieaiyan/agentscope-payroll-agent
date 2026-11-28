package io.agentscope.examples.payroll.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.agentscope.examples.payroll.db.support.TransactionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/mybatis-migration-prd.md §5.0.1：@Transactional 只能出现在登记表 migrated=true 的行上。
 *
 * <p>P0 阶段集合为空（试点两个仓储都是 L3）。给未迁移 store 加注解不仅不生效，还会多占一条
 * 连接，因此「多一个」与「少一个」都判失败。
 */
class TransactionWhitelistTest {

    private static final String[] SCANNED_PACKAGES = {
        "io.agentscope.examples.payroll",
        "io.agentscope.payroll.runtime",
        "io.agentscope.payroll.sandbox"
    };

    @Test
    @DisplayName("登记表 29 行且方法键唯一")
    void registryHas29UniqueMethods() {
        assertEquals(29, TransactionRegistry.ENTRIES.size());
        assertEquals(
                TransactionRegistry.ENTRIES.size(), TransactionRegistry.allKeys().size(),
                "登记表存在重复的 store#method");
        assertTrue(TransactionRegistry.migratedKeys().isEmpty(), "P0 阶段不应有已迁移行");
    }

    @Test
    @DisplayName("@Transactional 方法集合与登记表逐条相等")
    void transactionalMethodsMatchRegistry() {
        assertSameAsRegistry(annotatedMethods(mainClasses()), TransactionRegistry.migratedKeys());
    }

    @Test
    @DisplayName("负例：多一个注解即失败")
    void extraAnnotationIsRejected() {
        Set<String> annotated = Set.of("io.agentscope.examples.payroll.db.H2ChatStore#appendAssistant");
        assertThrows(
                AssertionError.class,
                () -> assertSameAsRegistry(annotated, TransactionRegistry.migratedKeys()));
    }

    @Test
    @DisplayName("负例：登记为已迁移却没有注解也失败")
    void missingAnnotationIsRejected() {
        Set<String> expected = Set.of("io.agentscope.examples.payroll.db.H2ChatStore#appendAssistant");
        assertThrows(AssertionError.class, () -> assertSameAsRegistry(Set.of(), expected));
    }

    @Test
    @DisplayName("事务方法体内不得出现 Executor / SSE / HTTP / 文件 IO")
    void transactionalBodiesHaveNoSideEffects() {
        List<String> violations = sideEffectViolations(mainClasses());
        assertTrue(violations.isEmpty(), "事务内只允许 DB 调用：" + violations);
    }

    @Test
    @DisplayName("负例：副作用守卫能抓到 Executor 与文件 IO")
    void sideEffectGuardCatchesViolations() {
        List<String> violations =
                sideEffectViolations(new ClassFileImporter().importClasses(SideEffectsExample.class));
        assertFalse(violations.isEmpty(), "守卫对示例类应当报违规");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("newSingleThreadExecutor")),
                violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("readString")), violations.toString());
    }

    @Test
    @DisplayName("类级 @Transactional 不允许（会波及未登记方法）")
    void classLevelTransactionalIsRejected() {
        List<String> classLevel =
                mainClasses().stream()
                        .filter(clazz -> clazz.isAnnotatedWith(Transactional.class))
                        .map(JavaClass::getName)
                        .sorted()
                        .toList();
        assertTrue(classLevel.isEmpty(), "事务注解只能落在登记表列出的方法上：" + classLevel);
    }

    private static void assertSameAsRegistry(Set<String> annotated, Set<String> expected) {
        Set<String> unexpected = new TreeSet<>(annotated);
        unexpected.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(annotated);
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            fail(
                    "@Transactional 与 §5.0.1 登记表不一致\n未登记的方法: "
                            + unexpected
                            + "\n缺少注解的已迁移方法: "
                            + missing);
        }
    }

    private static Set<String> annotatedMethods(JavaClasses classes) {
        return classes.stream()
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(Transactional.class))
                .map(TransactionWhitelistTest::key)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<String> sideEffectViolations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                if (!method.isAnnotatedWith(Transactional.class)) {
                    continue;
                }
                method.getMethodCallsFromSelf().stream()
                        .filter(call -> isSideEffect(call.getTarget().getOwner()))
                        .forEach(
                                call ->
                                        violations.add(
                                                key(method)
                                                        + " → "
                                                        + call.getTarget().getOwner().getName()
                                                        + '#'
                                                        + call.getTarget().getName()));
            }
        }
        return violations;
    }

    private static boolean isSideEffect(JavaClass owner) {
        String name = owner.getName();
        String simple = owner.getSimpleName();
        if (name.startsWith("java.util.concurrent.")) {
            return switch (simple) {
                case "Executor", "ExecutorService", "ScheduledExecutorService",
                        "ThreadPoolExecutor", "Executors", "CompletableFuture" -> true;
                default -> false;
            };
        }
        if (name.startsWith("java.nio.file.Files")
                || name.startsWith("java.io.File")
                || name.startsWith("java.io.PrintWriter")
                || name.startsWith("java.io.RandomAccessFile")) {
            return true;
        }
        if (name.startsWith("java.net.http.")
                || name.startsWith("okhttp3.")
                || name.startsWith("org.springframework.web.client.")
                || name.startsWith("org.apache.hc.")) {
            return true;
        }
        return name.equals("org.springframework.web.servlet.mvc.method.annotation.SseEmitter");
    }

    private static String key(JavaMethod method) {
        return method.getOwner().getName() + '#' + method.getName();
    }

    private static JavaClasses mainClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(SCANNED_PACKAGES);
    }

    /** 仅用于负例：不会被加载执行，ArchUnit 只解析字节码。 */
    static final class SideEffectsExample {

        @Transactional
        void touchesFiles() throws IOException {
            Files.readString(Path.of("unused"));
        }

        @Transactional
        void startsExecutor() {
            Executors.newSingleThreadExecutor().shutdown();
        }
    }
}