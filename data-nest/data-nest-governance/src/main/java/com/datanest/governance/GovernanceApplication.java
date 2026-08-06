package com.datanest.governance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.datanest.governance", "com.datanest.common", "com.datanest.task.core"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.datanest.alert.api")
@MapperScan({"com.datanest.governance.mapper", "com.datanest.task.core.mapper"})
public class GovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovernanceApplication.class, args);
    }
}
