package com.datanest.engineering;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.datanest.engineering", "com.datanest.common", "com.datanest.task.core",
        "com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api", "com.datanest.governance.api"})
// 微服务化 3.2：task-core 执行链路（同进程扫描进本服务）也经 EngineeringSyncJobApi 等 Feign 调用，
// 本服务需启用自身契约的 Feign client（lb://data-nest-engineering 自调用走负载均衡）。
// 微服务化 3.4：治理表跨域读写收进 governance 内部端点，启用 GovernanceDatasourceApi client。
@EnableFeignClients(basePackages = {"com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api",
        "com.datanest.governance.api"})
@MapperScan(basePackages = {"com.datanest.engineering.mapper", "com.datanest.task.core.mapper"})
public class EngineeringApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngineeringApplication.class, args);
    }
}
