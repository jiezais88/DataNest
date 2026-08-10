package com.datanest.realtime.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * 实时 CDC 库（datanest_realtime）Flyway 配置（Sprint 8 F2，第 5 个业务库）。
 * 与 alert 的 FlywayConfig 同模式：代码驱动（Boot 4 未引入 flyway autoconfigure 模块，
 * spring.flyway 的 yaml 配置不生效）。baselineOnMigrate：已有数据的库打基线标记跳过 V1.0.0，
 * 全新空库正常执行 V1.0.0 建表。后续脚本版本号从 1.1.0 起。
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    @DependsOn("dataSource")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }
}
