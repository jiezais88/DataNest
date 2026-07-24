package com.datanest.gateway.config;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaTokenConfig {

    /**
     * Sa-Token 全局过滤器 (WebFlux)
     * 校验 Token，放行登录接口
     */
    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude(
                        "/api/system/auth/login",
                        "/api/system/auth/logout"
                )
                .setAuth(obj -> {
                    SaRouter.match("/**", StpUtil::checkLogin);
                });
    }
}
