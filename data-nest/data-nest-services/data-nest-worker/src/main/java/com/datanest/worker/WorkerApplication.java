package com.datanest.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.datanest.worker", "com.datanest.task.core", "com.datanest.common",
        "com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api", "com.datanest.governance.api"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.datanest.alert.api", "com.datanest.system.api", "com.datanest.engineering.api",
        "com.datanest.governance.api"})
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
