package com.datanest.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
public class SaTokenConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /**
     * Sa-Token 全局过滤器 (WebFlux)
     * 校验 Token，放行登录接口，并统一处理未登录/权限异常。
     */
    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude(
                        "/api/system/auth/login",
                        "/api/system/auth/logout",
                        "/actuator/gateway/**",
                        "/actuator/health"
                )
                .setAuth(obj -> {
                    SaRouter.match("/**", StpUtil::checkLogin);
                })
                .setError(e -> {
                    SaHolder.getResponse().setHeader("Content-Type", CONTENT_TYPE_JSON);
                    if (e instanceof NotLoginException) {
                        SaHolder.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
                        return writeResult(Result.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage()));
                    }
                    if (e instanceof NotRoleException || e instanceof NotPermissionException) {
                        SaHolder.getResponse().setStatus(HttpStatus.FORBIDDEN.value());
                        return writeResult(Result.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage()));
                    }
                    SaHolder.getResponse().setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
                    return writeResult(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
                });
    }

    private static String writeResult(Result<?> result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize error response", ex);
        }
    }
}
