package io.agentscope.examples.payroll.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明接口所需权限；方法级注解优先于类级。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    String[] value();

    /** true 表示满足任一权限即可，false 表示必须全部满足。 */
    boolean any() default true;
}
