package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DorisConstants;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.task.core.config.DorisDataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python 沙箱通用连接注入解析器（Sprint 7 DG-10 方案 B）。
 * <p>
 * 把目标表数据源连接信息解析为统一的 conn.json 结构
 * {@code {type, host, port, user, password, database, schema}}，供 {@link PythonExecutor}
 * 注入沙箱（read_table 按 type 选驱动）。worker（质量执行）与 governance（测试脚本端点）共用。
 * <p>
 * 内置 Doris（datasourceId 空或 -1）无连接记录，连接信息取自 {@link DorisDataSourceConfig}；
 * 注册数据源经 GenericSqlExecutor Feign 读取 + 解密密码。目标表的 databaseName/schemaName
 * 非空时覆盖连接默认库/schema（质量规则目标表可能不在数据源默认库）。
 */
@Service
public class PythonConnectionResolver {

    private static final Logger logger = LoggerFactory.getLogger(PythonConnectionResolver.class);

    private final GenericSqlExecutor genericSqlExecutor;
    private final EncryptionConfig encryptionConfig;

    public PythonConnectionResolver(GenericSqlExecutor genericSqlExecutor, EncryptionConfig encryptionConfig) {
        this.genericSqlExecutor = genericSqlExecutor;
        this.encryptionConfig = encryptionConfig;
    }

    /**
     * 解析目标表数据源的通用连接信息（conn.json 内容）。
     *
     * @param datasourceId 目标表数据源 ID（null/-1 = 内置 Doris）
     * @param databaseName 目标表归属库名（非空覆盖连接默认库）
     * @param schemaName   目标表归属 schema（非空覆盖连接默认 schema，PG/Oracle 用）
     */
    public Map<String, Object> resolve(Long datasourceId, String databaseName, String schemaName) {
        if (datasourceId == null || datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return resolveDoris(databaseName);
        }
        DataSourceInfo ds;
        try {
            ds = genericSqlExecutor.getDatasource(datasourceId);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED,
                    "数据源不存在: " + datasourceId);
        }
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("type", ds.getType() == null ? "" : ds.getType().toLowerCase());
        conn.put("host", ds.getHost());
        conn.put("port", ds.getPort());
        conn.put("user", ds.getUsername());
        conn.put("password", encryptionConfig.decrypt(ds.getEncryptedPassword()));
        conn.put("database", StringUtils.hasText(databaseName) ? databaseName : ds.getDatabaseName());
        conn.put("schema", StringUtils.hasText(schemaName) ? schemaName : ds.getSchemaName());
        return conn;
    }

    /** 内置 Doris 连接（凭据取 DorisDataSourceConfig 静态配置，连接池 URL 仅细化 host/port/database） */
    private Map<String, Object> resolveDoris(String databaseName) {
        String host = DorisDataSourceConfig.currentHost() != null ? DorisDataSourceConfig.currentHost()
                : System.getProperty("datanest.doris.fe-host", "localhost");
        String port = DorisDataSourceConfig.currentPort() > 0 ? String.valueOf(DorisDataSourceConfig.currentPort())
                : System.getProperty("datanest.doris.fe-query-port", "9030");
        String user = DorisDataSourceConfig.currentUser() != null ? DorisDataSourceConfig.currentUser()
                : System.getProperty("datanest.doris.user", "root");
        String password = DorisDataSourceConfig.currentPassword() != null ? DorisDataSourceConfig.currentPassword()
                : System.getProperty("datanest.doris.password", "");
        String database = DorisDataSourceConfig.currentDatabase() != null ? DorisDataSourceConfig.currentDatabase()
                : System.getProperty("datanest.engineering.addax.target-database", "datanest");

        DataSource ds = DorisDataSourceConfig.getDataSource();
        if (ds != null) {
            try (Connection c = ds.getConnection()) {
                String url = c.getMetaData().getURL();
                // jdbc:mysql://host:port/db?...（Doris FE 走 MySQL 协议）
                if (StringUtils.hasText(url)) {
                    String remainder = url.substring(url.indexOf("//") + 2);
                    int slash = remainder.indexOf('/');
                    String hostPort = slash < 0 ? remainder : remainder.substring(0, slash);
                    int colon = hostPort.indexOf(':');
                    if (colon > 0) {
                        host = hostPort.substring(0, colon);
                        port = hostPort.substring(colon + 1);
                    } else if (!hostPort.isEmpty()) {
                        host = hostPort;
                    }
                    if (slash >= 0) {
                        String dbPart = remainder.substring(slash + 1);
                        int q = dbPart.indexOf('?');
                        String parsed = q < 0 ? dbPart : dbPart.substring(0, q);
                        if (StringUtils.hasText(parsed)) {
                            database = parsed;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("从 DorisDataSourceConfig 解析连接信息失败，降级到 system property", e);
            }
        }
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("type", "doris");
        conn.put("host", host);
        conn.put("port", Integer.parseInt(port));
        conn.put("user", user);
        conn.put("password", password);
        conn.put("database", StringUtils.hasText(databaseName) ? databaseName : database);
        conn.put("schema", null);
        return conn;
    }

    /**
     * 拼接 read_table 用的目标表全名：mysql/doris → database.table；postgresql/oracle → schema.table；
     * 前缀为空时仅表名（由沙箱按 conn 默认库/schema 兜底）。
     */
    public String buildFullTableName(Map<String, Object> conn, String databaseName,
                                     String schemaName, String tableName) {
        String type = String.valueOf(conn.getOrDefault("type", ""));
        if ("postgresql".equals(type) || "oracle".equals(type)) {
            Object schema = conn.get("schema");
            return schema != null && StringUtils.hasText(schema.toString())
                    ? schema + "." + tableName : tableName;
        }
        Object database = conn.get("database");
        return database != null && StringUtils.hasText(database.toString())
                ? database + "." + tableName : tableName;
    }
}
