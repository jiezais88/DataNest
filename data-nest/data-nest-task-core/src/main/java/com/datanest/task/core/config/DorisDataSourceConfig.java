package com.datanest.task.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Sprint 3 P1-4：Doris 数据源配置（HikariCP 连接池）
 * 替代 DorisSqlExecutor 里的 DriverManager.getConnection 每次新建连接
 * 池大小：max 10 / min 2（默认值，SQL 节点回调并发不会很高）
 */
@Configuration
public class DorisDataSourceConfig {

    @Value("${datanest.doris.fe-host:localhost}")
    private String dorisFeHost;

    @Value("${datanest.doris.fe-query-port:9030}")
    private int dorisFePort;

    @Value("${datanest.doris.user:root}")
    private String dorisUser;

    @Value("${datanest.doris.password:}")
    private String dorisPassword;

    @Value("${datanest.engineering.addax.target-database:datanest}")
    private String defaultDatabase;

    @Bean(name = "dorisDataSource")
    @ConfigurationProperties(prefix = "datanest.doris.hikari")
    public DataSource dorisDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                dorisFeHost, dorisFePort, defaultDatabase));
        config.setUsername(dorisUser);
        config.setPassword(dorisPassword);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // 默认池配置（也可通过 datanest.doris.hikari.* 覆盖）
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("datanest-doris-pool");
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(600000);     // 10 分钟
        config.setMaxLifetime(1800000);    // 30 分钟
        // Doris 推荐加这个：FE 查询端口对长连接友好
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        return new HikariDataSource(config);
    }
}
