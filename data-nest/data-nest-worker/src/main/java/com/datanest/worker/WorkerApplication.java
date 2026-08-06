package com.datanest.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.datanest.worker", "com.datanest.task.core", "com.datanest.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.datanest.alert.api")
@MapperScan("com.datanest.task.core.mapper")
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
