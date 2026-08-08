package com.datanest.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 接口文档（OpenAPI）自动装配（springdoc 3.x / Spring Boot 4）。
 * <p>
 * 业务服务显式引入 {@code springdoc-openapi-starter-webmvc-ui} 后生效：
 * 生成统一 OpenAPI 定义（标题/描述 + 网关前缀 server + Authorization 头安全方案）。
 * 文档 JSON 经网关聚合页（app-gateway swagger-ui）按服务切换查看；
 * {@code gateway-prefix} 使聚合页在线调试的请求路径带网关前缀（如 /api/engineering）。
 * <p>
 * 仅 Servlet 服务装配；网关（WebFlux）不装配本配置（网关只托管聚合 UI，无业务 API）。
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocsAutoConfiguration {

    /** 全局安全方案名（Authorization 头，对齐 sa-token token-name） */
    private static final String AUTH_SCHEME = "Authorization";

    @Bean
    public OpenAPI datanestOpenAPI(
            @Value("${datanest.docs.title:DataNest API}") String title,
            @Value("${datanest.docs.description:DataNest 数据平台接口文档}") String description,
            @Value("${datanest.docs.gateway-prefix:/}") String gatewayPrefix) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version("1.0"))
                // 经网关访问的前缀（如 /api/system），聚合页在线调试时拼到接口路径前
                .servers(List.of(new Server().url(gatewayPrefix)))
                .components(new Components().addSecuritySchemes(AUTH_SCHEME,
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name(AUTH_SCHEME)))
                .addSecurityItem(new SecurityRequirement().addList(AUTH_SCHEME));
    }
}
