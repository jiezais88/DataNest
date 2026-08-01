package com.datanest.engineering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * DolphinScheduler 3.4.2 客户端配置
 * 加载 shared-dolphinscheduler.yaml（datanest.dolphinscheduler.*）
 * 决策 ADR-S3-004：纯 HTTP RestTemplate，不引入 DS SDK
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "datanest.dolphinscheduler")
public class DolphinSchedulerConfig {

    /**
     * Sprint 3 性能6：默认 DS 项目 code（data-dev）。所有 fallback 都从这里读，避免散落 4 个魔法值。
     * 真要创建新项目时，DagProjectService 会用 DS /projects API 拿到独立 code 存到 dag_project.ds_project_code。
     */
    public static final Long DEFAULT_DS_PROJECT_CODE = 180191629157984L;

    /** DS API 基础 URL，例如 http://middleware-ds-api:12345/dolphinscheduler */
    private String apiUrl;

    /** DS 访问 Token（持久 token，不需每次重新登录） */
    private String token;

    /** 业务租户编码 */
    private String tenantCode;

    /** 节点回调工程服务的超时（秒） */
    private Integer callbackTimeoutSeconds = 1800;

    /**
     * 节点回调地址前缀（DS worker → DataNest 接收端）。
     * 决策 ADR-S3-012：回调走 gateway，不直连 engineering。
     *   - Docker 内网：默认 {@code http://app-gateway:8080/api/engineering}
     *   - Gateway 路由 {@code /api/engineering/**} + StripPrefix=1 → engineering
     *   - DS worker 与 gateway 在同一 datanest-net 网络中
     * 运维可按需覆盖（生产可改为 LB/内网域名）
     */
    private String callbackBaseUrl = "http://app-gateway:8080/api/engineering";

    @Bean(name = "dolphinSchedulerRestTemplate")
    public RestTemplate dolphinSchedulerRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // DS 接口普遍在 200ms 内返回；CONNECT 5s，READ 30s 给 schema init 余量
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(factory);
    }
}
