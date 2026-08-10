package com.datanest.realtime.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.realtime.dto.CdcSourceValidateResult;
import com.datanest.realtime.dto.CdcSourceTableDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CDC 源数据源预检服务：连通性 / binlog 开启 / binlog_format=ROW / 源库存在性。
 * <p>
 * 连接信息经 Feign 从 engineering 反查（fail-closed），密码用 common EncryptionConfig 解密，
 * 原生 JDBC 连接 MySQL 逐项检查。
 */
@Service
public class SourcePrecheckService {

    private static final Logger logger = LoggerFactory.getLogger(SourcePrecheckService.class);

    /** MySQL 系统库（listDatabases 过滤掉） */
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "performance_schema", "mysql", "sys");

    private final EngineeringDatasourceApi engineeringDatasourceApi;
    private final EncryptionConfig encryptionConfig;

    public SourcePrecheckService(EngineeringDatasourceApi engineeringDatasourceApi,
                                 EncryptionConfig encryptionConfig) {
        this.engineeringDatasourceApi = engineeringDatasourceApi;
        this.encryptionConfig = encryptionConfig;
    }

    /**
     * 预检源数据源：逐项检查，全部通过 success=true。
     * binlog 相关检查失败时调用方（start 流程）映射 8005；连接失败映射 8004。
     */
    public CdcSourceValidateResult validate(Long datasourceId, String sourceDatabase) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        List<CdcSourceValidateResult.CheckItem> checks = new ArrayList<>();

        try (Connection connection = openConnection(datasource)) {
            checks.add(new CdcSourceValidateResult.CheckItem("数据源连通性", true, "连接成功"));

            // binlog 是否开启
            String logBin = queryVariable(connection, "log_bin");
            boolean logBinOn = "ON".equalsIgnoreCase(logBin);
            checks.add(new CdcSourceValidateResult.CheckItem("binlog 开启", logBinOn,
                    logBinOn ? "log_bin=ON" : "log_bin=" + logBin + "，CDC 增量同步需要开启 binlog"));

            // binlog 格式必须 ROW
            String binlogFormat = queryVariable(connection, "binlog_format");
            boolean rowFormat = "ROW".equalsIgnoreCase(binlogFormat);
            checks.add(new CdcSourceValidateResult.CheckItem("binlog 格式为 ROW", rowFormat,
                    rowFormat ? "binlog_format=ROW" : "binlog_format=" + binlogFormat + "，CDC 需要 ROW 模式"));

            // 源库存在性（sourceDatabase 非空时）
            if (sourceDatabase != null && !sourceDatabase.isBlank()) {
                boolean exists = listDatabases(connection).contains(sourceDatabase);
                checks.add(new CdcSourceValidateResult.CheckItem("源库存在", exists,
                        exists ? "数据库 " + sourceDatabase + " 存在" : "数据库 " + sourceDatabase + " 不存在"));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("CDC 源数据源预检连接失败: datasourceId={}, error={}", datasourceId, e.getMessage());
            checks.add(new CdcSourceValidateResult.CheckItem("数据源连通性", false, "连接失败: " + e.getMessage()));
        }

        CdcSourceValidateResult result = new CdcSourceValidateResult();
        result.setChecks(checks);
        result.setSuccess(checks.stream().allMatch(c -> Boolean.TRUE.equals(c.getPassed())));
        return result;
    }

    /** 列出源数据源的全部业务库（过滤 MySQL 系统库） */
    public List<String> listDatabases(Long datasourceId) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        try (Connection connection = openConnection(datasource)) {
            return listDatabases(connection);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }

    /**
     * 反查数据源连接信息（fail-closed：Feign 异常直接包成 8004 传播）；
     * 本期仅支持 MySQL 源。start 组装 YAML 时也复用此方法取连接信息。
     */
    public DataSourceInfo getDatasource(Long datasourceId) {
        Result<DataSourceInfo> result;
        try {
            result = engineeringDatasourceApi.getById(datasourceId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "查询源数据源失败: " + e.getMessage());
        }
        DataSourceInfo datasource = result == null ? null : result.data();
        if (datasource == null) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED, "源数据源不存在: " + datasourceId);
        }
        if (!"MYSQL".equalsIgnoreCase(datasource.getType())) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "本期 CDC 仅支持 MySQL 源，当前类型: " + datasource.getType());
        }
        return datasource;
    }

    /** 解密数据源密码（start 组装 YAML 用） */
    public String decryptPassword(DataSourceInfo datasource) {
        return encryptionConfig.decrypt(datasource.getEncryptedPassword());
    }

    /** 打开 MySQL JDBC 连接（密码解密；超时 5s，避免预检长时间挂起） */
    private Connection openConnection(DataSourceInfo datasource) {
        String plainPassword = decryptPassword(datasource);
        String url = String.format(
                "jdbc:mysql://%s:%d/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000",
                datasource.getHost(), datasource.getPort());
        try {
            return DriverManager.getConnection(url, datasource.getUsername(), plainPassword);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }

    /** SHOW VARIABLES LIKE 'xxx'，查不到返回 null */
    private String queryVariable(Connection connection, String variable) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW VARIABLES LIKE '" + variable + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    /** SHOW DATABASES（过滤系统库） */
    private List<String> listDatabases(Connection connection) throws Exception {
        List<String> databases = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                String database = rs.getString(1);
                if (!SYSTEM_DATABASES.contains(database)) {
                    databases.add(database);
                }
            }
        }
        return databases;
    }

    /** 列出源库下的业务表（表名 + 约估行数，向导同步表勾选用；库不存在返回空列表） */
    public List<CdcSourceTableDTO> listTables(Long datasourceId, String database) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        try (Connection connection = openConnection(datasource);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT TABLE_NAME, COALESCE(TABLE_ROWS, 0) AS table_rows FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, database);
            List<CdcSourceTableDTO> tables = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CdcSourceTableDTO dto = new CdcSourceTableDTO();
                    dto.setTableName(rs.getString(1));
                    dto.setTableRows(rs.getLong(2));
                    tables.add(dto);
                }
            }
            return tables;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }
}
