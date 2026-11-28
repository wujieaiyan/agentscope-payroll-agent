package io.agentscope.examples.payroll.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * SQL 守卫（规格 §6 D7）：Mapper XML 与注解里的 SQL 只允许 {@code #{}} 占位。
 *
 * <p>四条规则都是离线检查——{@code ${}} 拼串、二级缓存、{@code SELECT *}、嵌套 select
 * 一旦进库，运行期只会表现成慢或数据泄露，不会报错。
 */
class MapperSqlGuardTest {

    private static final String MAPPER_XML_LOCATION = "classpath*:mapper/**/*.xml";
    private static final String MAPPER_PACKAGE = "io.agentscope.examples.payroll.db.mapper";
    private static final String IBATIS_ANNOTATION_PACKAGE = "org.apache.ibatis.annotations.";

    /** 允许出现的 {@code ${...}} 表达式；只收枚举式常量，不收任何来自外部输入的键。 */
    private static final Set<String> ALLOWED_DOLLAR_EXPRESSIONS = Set.of();

    private static final Pattern DOLLAR_EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern SELECT_STAR = Pattern.compile("(?is)\\bselect\\s+(?:distinct\\s+)?\\*");
    private static final Pattern NESTED_SELECT =
            Pattern.compile("(?is)<(?:association|collection)\\b[^>]*\\bselect\\s*=");

    @Test
    @DisplayName("resources/mapper 下的 XML 全部满足四条规则")
    void mapperXmlObeysRules() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(MAPPER_XML_LOCATION)) {
            scanned++;
            try (InputStream input = resource.getInputStream()) {
                String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                violations.addAll(inspect(resource.getDescription(), xml));
            }
        }
        assertTrue(violations.isEmpty(), "共扫描 " + scanned + " 个 XML：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("Mapper 注解 SQL 同样受守卫约束")
    void mapperAnnotationSqlObeysRules() {
        if (MapperSqlGuardTest.class.getResource("/" + MAPPER_PACKAGE.replace('.', '/')) == null) {
            return;
        }
        JavaClasses classes = new ClassFileImporter().importPackages(MAPPER_PACKAGE);
        List<String> violations = new ArrayList<>();
        for (JavaClass mapperClass : classes) {
            for (JavaMethod method : mapperClass.getMethods()) {
                for (JavaAnnotation<?> annotation : method.getAnnotations()) {
                    if (!annotation.getRawType().getPackageName().startsWith(IBATIS_ANNOTATION_PACKAGE)) {
                        continue;
                    }
                    for (String sql : annotationStatements(annotation)) {
                        violations.addAll(inspect(annotation.getRawType().getName(), sql));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("守卫能识别四类违规写法")
    void detectorRejectsViolations() {
        assertTrue(inspect("case", "SELECT id FROM audit_log ORDER BY ${sort}").stream()
                .anyMatch(violation -> violation.contains("${}")));
        assertTrue(inspect("case", "<mapper><cache/></mapper>").stream()
                .anyMatch(violation -> violation.contains("<cache")));
        assertTrue(inspect("case", "select * from audit_log").stream()
                .anyMatch(violation -> violation.contains("SELECT *")));
        assertTrue(
                inspect("case", "<association property=\"actor\" select=\"selectActor\"/>").stream()
                        .anyMatch(violation -> violation.contains("嵌套 select")));
    }

    @Test
    @DisplayName("守卫放行正确写法")
    void detectorAcceptsSafeSql() {
        String safe =
                """
                <select id="find" resultMap="row">
                  SELECT audit_id, action FROM audit_log WHERE actor_name = #{actor} LIMIT #{size}
                </select>
                <association property="actor" resultMap="actorMap"/>
                """;
        assertTrue(inspect("case", safe).isEmpty(), String.join("\n", inspect("case", safe)));
    }

    private static List<String> inspect(String where, String sql) {
        List<String> violations = new ArrayList<>();
        Matcher dollar = DOLLAR_EXPRESSION.matcher(sql);
        while (dollar.find()) {
            if (!ALLOWED_DOLLAR_EXPRESSIONS.contains(dollar.group(1).trim())) {
                violations.add(where + "：禁止 ${} 拼接（" + dollar.group() + "）");
            }
        }
        if (sql.toLowerCase(Locale.ROOT).contains("<cache")) {
            violations.add(where + "：禁止 <cache>/<cache-ref>，本地缓存已按 statement 关闭");
        }
        if (SELECT_STAR.matcher(sql).find()) {
            violations.add(where + "：禁止 SELECT *，列必须显式列出");
        }
        if (NESTED_SELECT.matcher(sql).find()) {
            violations.add(where + "：禁止嵌套 select（关联查询的 N+1 源）");
        }
        return violations;
    }

    private static List<String> annotationStatements(JavaAnnotation<?> annotation) {
        List<String> statements = new ArrayList<>();
        for (String property : annotation.getProperties().keySet()) {
            collectStrings(annotation.get(property).orElse(null), statements);
        }
        return statements;
    }

    private static void collectStrings(Object value, List<String> target) {
        if (value instanceof String text) {
            target.add(text);
        } else if (value instanceof Object[] array) {
            for (Object element : array) {
                collectStrings(element, target);
            }
        }
    }
}