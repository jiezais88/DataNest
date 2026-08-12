package com.datanest.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.datanest.system.mapper")
@SpringBootApplication(scanBasePackages = {"com.datanest.system", "com.datanest.common.internal"})
public class SystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
