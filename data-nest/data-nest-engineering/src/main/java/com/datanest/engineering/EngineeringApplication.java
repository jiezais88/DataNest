package com.datanest.engineering;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.datanest.engineering", "com.datanest.common", "com.datanest.task.core"})
@EnableFeignClients(basePackages = "com.datanest.alert.api")
@MapperScan({"com.datanest.engineering.mapper", "com.datanest.task.core.mapper"})
public class EngineeringApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngineeringApplication.class, args);
    }
}
