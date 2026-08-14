package com.datanest.governance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.datanest.governance", "com.datanest.common", "com.datanest.task.core",
        "com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api", "com.datanest.governance.api",
        "com.datanest.dataservice.api"})
@EnableDiscoveryClient
// 微服务化 4.2：task-core 执行链路（同进程扫描进本服务）经 QualityExecutionApi 等 Feign 回调治理契约，
// 本服务需启用自身契约的 Feign client（lb://data-nest-governance 自调用走负载均衡，同 engineering 3.2 先例）。
// 治理自身的查询/写路径仍走本地 Service（QualityCheckQueryService/CollectWriteService 等），不经 Feign。
@EnableFeignClients(basePackages = {"com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api",
        "com.datanest.governance.api", "com.datanest.dataservice.api"})
// 微服务化 4.4：task.core 治理实体/mapper 旧副本已删除，同名冲突消失，
// 回退全限定名生成器；治理 bean 名冲突不存在，仅扫本地 mapper。
@MapperScan("com.datanest.governance.mapper")
public class GovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovernanceApplication.class, args);
    }
}
