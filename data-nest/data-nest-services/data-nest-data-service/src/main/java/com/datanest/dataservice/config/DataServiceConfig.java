package com.datanest.dataservice.config;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.task.core.config.DorisDataSourceConfig;
import com.datanest.task.core.service.DorisSqlExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式声明 common / task-core 组件（数据服务扫描包不含这些包）。
 * <p>
 * - EncryptionConfig：数据源密码 AES 解密（@ConfigurationProperties(datanest.security.encryption)
 *   对 @Bean 方式注册同样生效，密钥由 shared-security.yaml 注入）。
 * - DorisDataSourceConfig：内置 Doris 连接信息注入静态字段（@Value + @PostConstruct，
 *   懒建 Hikari 连接池，不可达降级 DriverManager）。
 * - DorisSqlExecutor：内置 Doris SQL 执行器（无状态，内部经静态 getter 拿连接）。
 */
@Configuration
public class DataServiceConfig {

    @Bean
    public EncryptionConfig encryptionConfig() {
        return new EncryptionConfig();
    }

    @Bean
    public DorisDataSourceConfig dorisDataSourceConfig() {
        return new DorisDataSourceConfig();
    }

    @Bean
    public DorisSqlExecutor dorisSqlExecutor() {
        return new DorisSqlExecutor();
    }
}
