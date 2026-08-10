package com.datanest.realtime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Doris catalog 刷新服务：管道数据落湖后 REFRESH CATALOG，让 Doris 外部表感知新表/新数据。
 * 原生 JDBC 走 Doris FE MySQL 协议（驱动复用 mysql-connector-j）。
 */
@Service
public class DorisCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(DorisCatalogService.class);

    @Value("${datanest.doris.fe-host}")
    private String feHost;

    @Value("${datanest.doris.fe-query-port}")
    private Integer feQueryPort;

    @Value("${datanest.doris.user}")
    private String user;

    @Value("${datanest.doris.password}")
    private String password;

    /** Doris 外部 catalog 名（M0 已建好，默认 datalake_catalog） */
    @Value("${datanest.realtime.iceberg.catalog-name:datalake_catalog}")
    private String catalogName;

    /** 刷新 Doris 外部 catalog（REFRESH CATALOG xxx） */
    public void refreshCatalog() {
        String url = String.format("jdbc:mysql://%s:%d/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000&socketTimeout=30000", feHost, feQueryPort);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("REFRESH CATALOG " + catalogName);
            logger.info("Doris catalog 刷新成功: {}", catalogName);
        } catch (Exception e) {
            throw new IllegalStateException("Doris catalog 刷新失败: " + e.getMessage(), e);
        }
    }
}
