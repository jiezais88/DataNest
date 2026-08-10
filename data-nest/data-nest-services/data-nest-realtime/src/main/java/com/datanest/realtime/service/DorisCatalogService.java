package com.datanest.realtime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /** catalog 级系统库（listLakeDatabases 过滤掉，不作为湖仓目标库候选） */
    private static final Set<String> CATALOG_SYSTEM_SCHEMAS = Set.of("information_schema", "mysql");

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

    /** 列出湖仓库名（SHOW DATABASES FROM catalog，Doris 4.1.3 实测可用；过滤系统 schema） */
    public List<String> listLakeDatabases() {
        String url = String.format("jdbc:mysql://%s:%d/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000&socketTimeout=30000", feHost, feQueryPort);
        List<String> databases = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW DATABASES FROM " + catalogName)) {
            while (rs.next()) {
                String database = rs.getString(1);
                if (!CATALOG_SYSTEM_SCHEMAS.contains(database)) {
                    databases.add(database);
                }
            }
            return databases;
        } catch (Exception e) {
            throw new IllegalStateException("查询湖仓库列表失败: " + e.getMessage(), e);
        }
    }
}
