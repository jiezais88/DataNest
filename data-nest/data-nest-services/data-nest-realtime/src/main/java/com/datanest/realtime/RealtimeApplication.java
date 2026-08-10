package com.datanest.realtime;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 实时 CDC 服务启动类（Sprint 8 F2）。
 * <p>
 * 只扫描 com.datanest.realtime 与 com.datanest.common.internal（内部令牌过滤器/Feign 拦截器/容错组件），
 * 另扫描消费的 api 包以装配 Feign fallbackFactory（api 包仅接口/DTO/fallback，扫描安全）；
 * common 的 Jackson / 异常处理 / Sa-Token 公共组件均通过自动配置装配，无需全包扫描
 * （避免误装配 SchedulerClient 等带强制配置项的组件）。
 * EncryptionConfig 不在扫描包内，由本服务 config 包显式声明 @Bean（见 RealtimeConfig）。
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.datanest.realtime", "com.datanest.common.internal",
        "com.datanest.engineering.api"})
@EnableFeignClients(basePackages = {"com.datanest.engineering.api"})
@EnableScheduling // CDC 管道运行状态监控轮询（CdcMonitorService）
@MapperScan("com.datanest.realtime.mapper")
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
