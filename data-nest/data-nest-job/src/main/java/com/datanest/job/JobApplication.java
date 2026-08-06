package com.datanest.job;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.datanest.job", "com.datanest.task.core", "com.datanest.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.datanest.alert.api", "com.datanest.system.api"})
@MapperScan("com.datanest.task.core.mapper")
public class JobApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobApplication.class, args);
    }
}
