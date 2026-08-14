package com.datanest.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志埋点注解（Sprint 11 F1，技术文档 D-3）。
 * <p>
 * 标注在写接口/关键操作 Controller 方法上，由 {@link AuditLogAspect} 环绕切面异步采集落库，
 * 写入失败 fail-open（仅 warn 日志，不阻断业务）。
 * <p>
 * {@code resourceId} / {@code resourceName} / {@code content} 支持 SpEL 表达式，
 * 上下文提供方法参数名（编译已开启 -parameters）与 {@code #result}（方法返回值）。
 * 例：{@code @AuditLog(resourceType = DATASOURCE, opType = CREATE, resourceName = "#request.name")}。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 资源类型（必填） */
    AuditResourceType resourceType();

    /** 操作类型（必填） */
    AuditOpType opType();

    /** 资源 ID SpEL（可选），如 {@code "#id"} */
    String resourceId() default "";

    /** 资源名称 SpEL（可选），如 {@code "#request.name"}；缺省时切面尝试从入参提取 name/username */
    String resourceName() default "";

    /** 操作内容 SpEL（可选），如 SQL 摘要、改级前后等级 */
    String content() default "";
}
