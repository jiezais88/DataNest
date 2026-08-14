package com.datanest.common.audit;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

/**
 * 审计日志环绕切面（Sprint 11 F1，技术文档 D-3）。
 * <p>
 * 采集操作人/资源/结果/IP 组装 {@link AuditLogEvent}，异步经 {@link AuditLogRecorder} 落库；
 * 采集与写入全程 fail-open——任何异常只打 warn，绝不阻断业务方法。
 */
@Aspect
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final int MAX_ERROR_MESSAGE = 500;

    private final ObjectProvider<AuditLogRecorder> recorderProvider;
    private final ExecutorService executor;

    public AuditLogAspect(ObjectProvider<AuditLogRecorder> recorderProvider, ExecutorService executor) {
        this.recorderProvider = recorderProvider;
        this.executor = executor;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        Long operatorId = currentUserId();
        String operatorName = currentUsername();
        String clientIp = currentClientIp();

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                Method method = ((MethodSignature) pjp.getSignature()).getMethod();
                AuditLogEvent event = buildEvent(pjp, method, auditLog, operatorId, operatorName, clientIp, result, error);
                executor.execute(() -> {
                    try {
                        recorderProvider.ifAvailable(r -> r.record(event));
                    } catch (Exception e) {
                        log.warn("审计日志写入失败（fail-open）：opType={}, resourceType={}", event.opType(), event.resourceType(), e);
                    }
                });
            } catch (Exception e) {
                log.warn("审计日志采集失败（fail-open）：{}", e.getMessage());
            }
        }
    }

    private AuditLogEvent buildEvent(ProceedingJoinPoint pjp, Method method, AuditLog auditLog,
                                     Long operatorId, String operatorName, String clientIp,
                                     Object result, Throwable error) {
        Object[] args = pjp.getArgs();
        String resourceId = resolve(auditLog.resourceId(), method, args, result);
        String resourceName = resolve(auditLog.resourceName(), method, args, result);
        if (resourceName == null || resourceName.isBlank()) {
            resourceName = defaultResourceName(args);
        }
        String content = resolve(auditLog.content(), method, args, result);

        String opResult = error == null ? AuditLogEvent.RESULT_SUCCESS : AuditLogEvent.RESULT_FAILURE;
        String errorMessage = error == null ? null : truncate(error.getMessage(), MAX_ERROR_MESSAGE);

        return new AuditLogEvent(operatorId, operatorName,
                auditLog.opType().name(), auditLog.resourceType().name(),
                resourceId, resourceName, content, opResult, errorMessage, clientIp);
    }

    /** 解析 SpEL：上下文提供参数名 + #result；解析失败返回 null（fail-open） */
    private String resolve(String expression, Method method, Object[] args, Object result) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            for (int i = 0; i < args.length; i++) {
                String name = i < parameters.length && parameters[i].isNamePresent()
                        ? parameters[i].getName()
                        : "arg" + i;
                ctx.setVariable(name, args[i]);
            }
            ctx.setVariable("result", result);
            Object value = SPEL_PARSER.parseExpression(expression).getValue(ctx);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("审计 SpEL 解析失败：expression={}, reason={}", expression, e.getMessage());
            return null;
        }
    }

    /** 未显式指定 resourceName 时，尝试从入参首个含 name/username 的对象提取 */
    private String defaultResourceName(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String v = tryRead(arg, "getName");
            if (v == null) {
                v = tryRead(arg, "getUsername");
            }
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private String tryRead(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            Object value = m.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentUsername() {
        try {
            Object username = StpUtil.getSession().get("username");
            return username == null ? null : String.valueOf(username);
        } catch (Exception e) {
            return null;
        }
    }

    private String currentClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
