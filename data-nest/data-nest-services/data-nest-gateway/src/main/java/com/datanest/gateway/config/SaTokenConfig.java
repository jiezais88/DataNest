package com.datanest.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;

@Configuration
public class SaTokenConfig {

    private static final String CONTENT_TYPE_JSON = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8";

    /**
     * Sa-Token 全局过滤器 (WebFlux)
     * 校验 Token，放行登录接口，并统一处理未登录/权限异常。
     * 决策 ADR-S3-FJ：序列化使用 fastjson2
     */
    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude(
                        "/api/system/auth/login",
                        "/api/system/auth/logout",
                        "/actuator/gateway/**",
                        "/actuator/health",
                        // 决策 ADR-S3-012：DS 回调内部接口走 gateway，不鉴权
                        // 决策 ADR-S3-008：依赖 Docker 网络隔离（datanest-net）
                        "/api/engineering/dev/internal/**",
                        "/api/worker/dev/internal/**",
                        // 接口文档（springdoc 聚合页 + 各服务 OpenAPI JSON）：文档资源匿名可读，
                        // 业务接口调试仍需用户在 swagger-ui 配置 Authorization 头（sa-token 保护不变）
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/api/*/v3/api-docs/**",
                        "/favicon.ico"
                )
                .setAuth(obj -> {
                    SaRouter.match("/**", StpUtil::checkLogin);
                })
                .setError(e -> {
                    SaHolder.getResponse().setHeader("Content-Type", CONTENT_TYPE_JSON);
                    ServerHttpResponse nativeResponse = (ServerHttpResponse) SaHolder.getResponse().getSource();
                    if (e instanceof NotLoginException) {
                        nativeResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
                        return writeResult(Result.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage()));
                    }
                    if (e instanceof NotRoleException || e instanceof NotPermissionException) {
                        nativeResponse.setStatusCode(HttpStatus.FORBIDDEN);
                        return writeResult(Result.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage()));
                    }
                    nativeResponse.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return writeResult(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
                });
    }

    /**
     * Sa-Token Reactor 模式下，setError 返回 String 即可，
     * 框架会自动用 String 写响应体。我们用 fastjson2 序列化 Result。
     */
    private static String writeResult(Result<?> result) {
        return JSON.toJSONString(result);
    }
}
