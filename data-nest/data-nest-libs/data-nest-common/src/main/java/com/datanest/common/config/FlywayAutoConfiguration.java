package com.datanest.common.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * 持库服务 Flyway 统一自动配置（替代各服务逐字复制的 FlywayConfig）。
 * <p>
 * 背景：Boot 4 未引入 flyway autoconfigure 模块，spring.flyway 的 yaml 配置不生效，
 * 各持库服务（system/alert/engineering/governance/realtime/data-service）此前各自维护
 * 一份逐字相同的 {@code FlywayConfig}（仅包名不同）。此处统一为共享自动配置。
 * <p>
 * 行为与原配置完全一致：{@code baselineOnMigrate} 让已有数据的库打基线标记跳过 V1.0.0，
 * 全新空库正常执行 V1.0.0 建表；{@code @DependsOn("dataSource")} 保证数据源就绪后再迁移。
 * <p>
 * 条件约束：
 * <ul>
 *   <li>{@link ConditionalOnClass}——仅在消费方引入 flyway-core（经中间父 pom）时生效，
 *       无库服务（worker/job/gateway）与 Flyway 无关自然不加载；</li>
 *   <li>{@link ConditionalOnBean}——需要容器中已有 DataSource；</li>
 *   <li>{@link ConditionalOnMissingBean}——服务若仍有自定义 Flyway bean 则本地优先（防御性兜底）。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(Flyway.class)
public class FlywayAutoConfiguration {

    /**
     * 注意：{@code @ConditionalOnBean(DataSource.class)} 放在方法级而非类级——
     * 方法级条件在 bean 定义处理阶段评估（所有 DataSource bean 定义已注册），
     * 类级条件受自动配置加载顺序影响，可能误判导致 Flyway 迁移不执行。
     */
    @Bean(initMethod = "migrate")
    @DependsOn("dataSource")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }
}
