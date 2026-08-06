package com.datanest.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 告警服务启动类。
 * <p>
 * 只扫描 com.datanest.alert 与 com.datanest.common.internal（内部令牌过滤器/Feign 拦截器）；
 * common 的 Jackson / 异常处理 / Sa-Token 公共组件均通过自动配置装配，无需全包扫描
 * （避免误装配 SchedulerClient 等带强制配置项的组件）。
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.datanest.alert", "com.datanest.common.internal"})
@EnableFeignClients(basePackages = {"com.datanest.system.api", "com.datanest.engineering.api", "com.datanest.governance.api"})
public class AlertApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertApplication.class, args);
    }
}
