package com.datanest.dataservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 数据服务启动类（Sprint 10：SQL 查询终端 + 数据 API + 实时订阅）。
 * <p>
 * 扫描约定（对齐 realtime/governance）：只扫描自身包 + com.datanest.common.internal（内部令牌过滤器/Feign 拦截器）
 * + 消费的 api 包（Feign fallbackFactory 装配）。task-core 的 DorisDataSourceConfig/DorisSqlExecutor
 * 由 DataServiceConfig 显式 @Bean 声明（避免全扫 task.core 引入质量/告警等无关组件）。
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.datanest.dataservice", "com.datanest.common.internal",
        "com.datanest.engineering.api", "com.datanest.system.api", "com.datanest.governance.api"})
@EnableFeignClients(basePackages = {"com.datanest.engineering.api", "com.datanest.system.api",
        "com.datanest.governance.api"})
@MapperScan("com.datanest.dataservice.mapper")
public class DataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
