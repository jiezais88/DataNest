package com.datanest.task.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Sprint 3 P1-4：Doris 数据源配置（HikariCP 连接池）
 *
 * 设计要点：
 * - 不用 @Bean：避免 Spring 启动时强制初始化连接（datanest.doris.fe-host 可能不可达，会导致整个 Spring 上下文启动失败）
 * - 在 @PostConstruct 里把 @Value 注入的值搬给静态字段，让 DorisSqlExecutor 按需拿
 * - 第一次拿连接池时初始化；如果失败（doris 不可达），降级为 null，调用方走 DriverManager 直连
 * - 启动时打 log 让用户知道 doris 不可达（不静默失败）
 */
@Configuration
public class DorisDataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(DorisDataSourceConfig.class);

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

    private static String host;
    private static int port;
    private static String user;
    private static String password;
    private static String database;

    private static volatile DataSource cachedDataSource;
    private static volatile boolean initFailed = false;

    @PostConstruct
    public void init() {
        host = dorisFeHost;
        port = dorisFePort;
        user = dorisUser;
        password = dorisPassword;
        database = defaultDatabase;
        logger.info("Doris DataSource 配置加载: host={}:{}, user={}, db={}", host, port, user, database);
    }

    /**
     * 按需拿连接池（懒加载）。
     * @return DataSource；doris 不可达时返回 null，调用方降级到 DriverManager
     */
    public static DataSource getDataSource() {
        if (cachedDataSource != null) return cachedDataSource;
        if (initFailed) return null;
        synchronized (DorisDataSourceConfig.class) {
            if (cachedDataSource != null) return cachedDataSource;
            if (initFailed) return null;
            try {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(String.format(
                        "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000",
                        host, port, database));
                config.setUsername(user);
                config.setPassword(password);
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setPoolName("datanest-doris-pool");
                config.setConnectionTimeout(5000);
                config.setIdleTimeout(600000);
                config.setMaxLifetime(1800000);
                config.setInitializationFailTimeout(-1);  // 启动时连接失败不抛异常
                cachedDataSource = new HikariDataSource(config);
                logger.info("Doris DataSource 连接池初始化成功");
                return cachedDataSource;
            } catch (Exception e) {
                logger.warn("Doris DataSource 初始化失败（doris 不可达），降级到 DriverManager 直连模式: {}",
                        e.getMessage());
                initFailed = true;
                return null;
            }
        }
    }
}
