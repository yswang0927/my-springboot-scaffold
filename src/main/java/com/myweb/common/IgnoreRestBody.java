package com.myweb.common;

import java.lang.annotation.*;

/**
 * 忽略RestBody注解，用于在Controller方法上，表示该方法不需要处理RestBody注解。
 */
@Documented
@Inherited
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoreRestBody {
}
