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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CDC 源数据源预检服务：连通性 / 增量日志开启 / 复制权限 / 源库存在性。
 * <p>
 * 支持 MySQL（binlog + binlog_format=ROW）与 PostgreSQL（wal_level=logical + replication 权限，
 * 本期仅支持 public schema）两类源；连接信息经 Feign 从 engineering 反查（fail-closed），
 * 密码用 common EncryptionConfig 解密，原生 JDBC 逐项检查。
 */
@Service
public class SourcePrecheckService {

    private static final Logger logger = LoggerFactory.getLogger(SourcePrecheckService.class);

    /** 支持 CDC 源的数据源类型 */
    public static final String TYPE_MYSQL = "MYSQL";
    public static final String TYPE_POSTGRESQL = "POSTGRESQL";

    /** MySQL 系统库（listDatabases 过滤掉） */
    private static final Set<String> MYSQL_SYSTEM_DATABASES = Set.of(
            "information_schema", "performance_schema", "mysql", "sys");

    /** PG 维护库（列库/预检时连接，不承载业务表） */
    private static final String PG_MAINTENANCE_DB = "postgres";

    /** PG 本期仅支持 public schema（YAML 的 tables/route 均按 public 组装） */
    public static final String PG_SCHEMA = "public";

    private final EngineeringDatasourceApi engineeringDatasourceApi;
    private final EncryptionConfig encryptionConfig;

    public SourcePrecheckService(EngineeringDatasourceApi engineeringDatasourceApi,
                                 EncryptionConfig encryptionConfig) {
        this.engineeringDatasourceApi = engineeringDatasourceApi;
        this.encryptionConfig = encryptionConfig;
    }

    /**
     * 预检源数据源：逐项检查，全部通过 success=true。
     * 增量日志相关检查失败时调用方（start 流程）映射 8005；连接失败映射 8004。
     */
    public CdcSourceValidateResult validate(Long datasourceId, String sourceDatabase) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        List<CdcSourceValidateResult.CheckItem> checks = new ArrayList<>();

        // PG 预检连维护库 postgres（源库存在性单独查 pg_database）；MySQL 不指定库
        String connectDb = isPostgres(datasource) ? PG_MAINTENANCE_DB : null;
        try (Connection connection = openConnection(datasource, connectDb)) {
            checks.add(new CdcSourceValidateResult.CheckItem("数据源连通性", true, "连接成功"));
            if (isPostgres(datasource)) {
                fillPostgresChecks(connection, sourceDatabase, checks);
            } else {
                fillMysqlChecks(connection, sourceDatabase, checks);
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

    /** MySQL 检查项：binlog 开启 / binlog_format=ROW / 源库存在 */
    private void fillMysqlChecks(Connection connection, String sourceDatabase,
                                 List<CdcSourceValidateResult.CheckItem> checks) throws Exception {
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
            boolean exists = listMysqlDatabases(connection).contains(sourceDatabase);
            checks.add(new CdcSourceValidateResult.CheckItem("源库存在", exists,
                    exists ? "数据库 " + sourceDatabase + " 存在" : "数据库 " + sourceDatabase + " 不存在"));
        }
    }

    /** PG 检查项：wal_level=logical / 复制权限（replication 角色或超级用户）/ 源库存在 */
    private void fillPostgresChecks(Connection connection, String sourceDatabase,
                                    List<CdcSourceValidateResult.CheckItem> checks) throws Exception {
        // wal_level 必须 logical（逻辑解码前提）
        String walLevel;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW wal_level")) {
            walLevel = rs.next() ? rs.getString(1) : null;
        }
        boolean logicalOn = "logical".equalsIgnoreCase(walLevel);
        checks.add(new CdcSourceValidateResult.CheckItem("WAL 逻辑复制开启", logicalOn,
                logicalOn ? "wal_level=logical"
                        : "wal_level=" + walLevel + "，CDC 增量同步需要 wal_level=logical"));

        // 当前用户复制权限（rolreplication 属性或超级用户；
        // 注意 pg_has_role 的权限类型只有 USAGE/MEMBER/SET，查 'replication' 会报 unrecognized privilege type）
        boolean hasReplication;
        String currentUser;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT current_user, COALESCE((SELECT rolreplication OR rolsuper "
                             + "FROM pg_roles WHERE rolname = current_user), false)")) {
            rs.next();
            currentUser = rs.getString(1);
            hasReplication = rs.getBoolean(2);
        }
        checks.add(new CdcSourceValidateResult.CheckItem("复制权限", hasReplication,
                hasReplication ? "用户 " + currentUser + " 具备 replication/超级用户权限"
                        : "用户 " + currentUser + " 无 replication 权限，CDC 需要 replication 角色或超级用户"));

        // 源库存在性（sourceDatabase 非空时）
        if (sourceDatabase != null && !sourceDatabase.isBlank()) {
            boolean exists = listPostgresDatabases(connection).contains(sourceDatabase);
            checks.add(new CdcSourceValidateResult.CheckItem("源库存在", exists,
                    exists ? "数据库 " + sourceDatabase + " 存在" : "数据库 " + sourceDatabase + " 不存在"));
        }
    }

    /** 列出源数据源的全部业务库（MySQL 过滤系统库；PG 过滤模板库、保留 postgres 维护库） */
    public List<String> listDatabases(Long datasourceId) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        String connectDb = isPostgres(datasource) ? PG_MAINTENANCE_DB : null;
        try (Connection connection = openConnection(datasource, connectDb)) {
            return isPostgres(datasource) ? listPostgresDatabases(connection) : listMysqlDatabases(connection);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }

    /**
     * 反查数据源连接信息（fail-closed：Feign 异常直接包成 8004 传播）；
     * 本期仅支持 MySQL / PostgreSQL 源。start 组装 YAML 时也复用此方法取连接信息。
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
        if (!TYPE_MYSQL.equalsIgnoreCase(datasource.getType())
                && !TYPE_POSTGRESQL.equalsIgnoreCase(datasource.getType())) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "本期 CDC 仅支持 MySQL / PostgreSQL 源，当前类型: " + datasource.getType());
        }
        return datasource;
    }

    /** 数据源是否 PostgreSQL 类型（getDatasource 已保证只可能是 MYSQL/POSTGRESQL） */
    public boolean isPostgres(DataSourceInfo datasource) {
        return TYPE_POSTGRESQL.equalsIgnoreCase(datasource.getType());
    }

    /** 解密数据源密码（start 组装 YAML 用） */
    public String decryptPassword(DataSourceInfo datasource) {
        return encryptionConfig.decrypt(datasource.getEncryptedPassword());
    }

    /**
     * 打开源库 JDBC 连接（密码解密；超时 5s，避免预检长时间挂起）。
     * MySQL 不指定库（connectDb 传 null）；PG 必须指定库（列库/预检连维护库 postgres，列表连目标库）。
     */
    private Connection openConnection(DataSourceInfo datasource, String connectDb) {
        String plainPassword = decryptPassword(datasource);
        String url;
        if (isPostgres(datasource)) {
            // PG 的 connectTimeout/socketTimeout 单位为秒
            url = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=5",
                    datasource.getHost(), datasource.getPort(), connectDb);
        } else {
            url = String.format(
                    "jdbc:mysql://%s:%d/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000",
                    datasource.getHost(), datasource.getPort());
        }
        try {
            return DriverManager.getConnection(url, datasource.getUsername(), plainPassword);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }

    /** SHOW VARIABLES LIKE 'xxx'（MySQL），查不到返回 null */
    private String queryVariable(Connection connection, String variable) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW VARIABLES LIKE '" + variable + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    /** MySQL：SHOW DATABASES（过滤系统库） */
    private List<String> listMysqlDatabases(Connection connection) throws Exception {
        List<String> databases = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                String database = rs.getString(1);
                if (!MYSQL_SYSTEM_DATABASES.contains(database)) {
                    databases.add(database);
                }
            }
        }
        return databases;
    }

    /** PG：pg_database（过滤模板库，保留 postgres 维护库——用户业务表可能直接建在其中） */
    private List<String> listPostgresDatabases(Connection connection) throws Exception {
        List<String> databases = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname")) {
            while (rs.next()) {
                databases.add(rs.getString(1));
            }
        }
        return databases;
    }

    /** 列出源库下的业务表（表名 + 约估行数 + 主键列，向导同步表勾选用；库不存在返回空列表） */
    public List<CdcSourceTableDTO> listTables(Long datasourceId, String database) {
        DataSourceInfo datasource = getDatasource(datasourceId);
        // PG 列表必须连目标库（库不存在时连接即失败，按连接失败报 8004）；MySQL 不指定库
        String connectDb = isPostgres(datasource) ? database : null;
        try (Connection connection = openConnection(datasource, connectDb)) {
            List<CdcSourceTableDTO> tables = isPostgres(datasource)
                    ? listPostgresTables(connection) : listMysqlTables(connection, database);
            fillPrimaryKeys(connection, datasource, database, tables);
            return tables;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_SOURCE_CONNECTION_FAILED,
                    "源数据源连接失败: " + e.getMessage());
        }
    }

    /** MySQL：information_schema.TABLES 查业务表 + 约估行数 */
    private List<CdcSourceTableDTO> listMysqlTables(Connection connection, String database) throws Exception {
        List<CdcSourceTableDTO> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT TABLE_NAME, COALESCE(TABLE_ROWS, 0) AS table_rows FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CdcSourceTableDTO dto = new CdcSourceTableDTO();
                    dto.setTableName(rs.getString(1));
                    dto.setTableRows(rs.getLong(2));
                    tables.add(dto);
                }
            }
        }
        return tables;
    }

    /** PG：仅 public schema 的 BASE TABLE + pg_class.reltuples 约估行数（未 ANALYZE 为 -1，按 0 计） */
    private List<CdcSourceTableDTO> listPostgresTables(Connection connection) throws Exception {
        List<CdcSourceTableDTO> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT t.table_name, GREATEST(COALESCE(c.reltuples, 0), 0)::bigint AS table_rows "
                        + "FROM information_schema.tables t "
                        + "LEFT JOIN pg_class c ON c.relname = t.table_name "
                        + "AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = t.table_schema) "
                        + "WHERE t.table_schema = '" + PG_SCHEMA + "' AND t.table_type = 'BASE TABLE' "
                        + "ORDER BY t.table_name")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CdcSourceTableDTO dto = new CdcSourceTableDTO();
                    dto.setTableName(rs.getString(1));
                    dto.setTableRows(rs.getLong(2));
                    tables.add(dto);
                }
            }
        }
        return tables;
    }

    /** 回填各表主键列（按主键列序排序，逗号拼接；无主键保持 null） */
    private void fillPrimaryKeys(Connection connection, DataSourceInfo datasource,
                                 String database, List<CdcSourceTableDTO> tables) throws Exception {
        if (tables.isEmpty()) {
            return;
        }
        Map<String, List<String>> pkColumns = new LinkedHashMap<>();
        if (isPostgres(datasource)) {
            // PG：table_constraints JOIN key_column_usage 取 public schema 主键
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT kcu.table_name, kcu.column_name "
                            + "FROM information_schema.table_constraints tc "
                            + "JOIN information_schema.key_column_usage kcu "
                            + "ON tc.constraint_name = kcu.constraint_name "
                            + "AND tc.table_schema = kcu.table_schema AND tc.table_name = kcu.table_name "
                            + "WHERE tc.table_schema = '" + PG_SCHEMA + "' AND tc.constraint_type = 'PRIMARY KEY' "
                            + "ORDER BY kcu.table_name, kcu.ordinal_position")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pkColumns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }
        } else {
            // MySQL：KEY_COLUMN_USAGE 按 ORDINAL_POSITION 排序
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                            + "WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pkColumns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }
        }
        for (CdcSourceTableDTO table : tables) {
            List<String> columns = pkColumns.get(table.getTableName());
            if (columns != null && !columns.isEmpty()) {
                table.setPrimaryKey(String.join(",", columns));
            }
        }
    }
}
