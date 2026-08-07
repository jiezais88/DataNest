package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.governance.api.MetadataWriteApi;
import com.datanest.governance.api.dto.MetadataRefreshIfExistsRequest;
import com.datanest.governance.api.dto.MetadataRegisterColumnDTO;
import com.datanest.governance.api.dto.MetadataRegisterRequest;
import com.datanest.governance.api.dto.MetadataRemoveRequest;
import com.datanest.task.core.dto.SourceTableDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据注册核心（worker 侧，同步/DAG 节点用）。
 * <p>
 * 微服务化 4.2：metadata_table/metadata_column 的读写全部改为经 {@link MetadataWriteApi}
 * Feign 调 app-governance（register 一个事务端点 / refresh-if-exists / remove）；
 * Doris 连接、information_schema 列提取与 SQL 解析仍留在本地。
 * 容错：远程写全部 RemoteCalls 降级（元数据注册失败不影响执行结果，对齐原调用方
 * try-catch 语义）；本地 Doris 读取失败仍按原语义抛 BusinessException。
 */
@Service
public class MetadataRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataRegistrationService.class);

    @Value("${datanest.engineering.addax.target-database:datanest}")
    private String targetDatabase;

    private final EngineeringSyncJobApi syncJobApi;
    private final MetadataWriteApi metadataWriteApi;

    public MetadataRegistrationService(EngineeringSyncJobApi syncJobApi,
                                       MetadataWriteApi metadataWriteApi) {
        this.syncJobApi = syncJobApi;
        this.metadataWriteApi = metadataWriteApi;
    }

    /** Sprint 3 P1-4：懒拿连接（同 DorisSqlExecutor） */
    private Connection dorisConn() throws java.sql.SQLException {
        javax.sql.DataSource ds = com.datanest.task.core.config.DorisDataSourceConfig.getDataSource();
        if (ds != null) return ds.getConnection();
        // 降级
        String host = System.getProperty("datanest.doris.fe-host", "localhost");
        String portStr = System.getProperty("datanest.doris.fe-query-port", "9030");
        String user = System.getProperty("datanest.doris.user", "root");
        String password = System.getProperty("datanest.doris.password", "");
        String database = System.getProperty("datanest.engineering.addax.target-database", "datanest");
        String url = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000",
                host, portStr, database);
        return java.sql.DriverManager.getConnection(url, user, password);
    }

    public void register(Long syncJobId) {
        // 微服务化 3.2：sync_job 定义经 engineering 远程读取（fail-fast，读不到直接抛错）
        Result<SyncJobInfo> jobResult = syncJobApi.getById(syncJobId);
        SyncJobInfo job = jobResult == null ? null : jobResult.data();
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }

        String targetDb = resolveTargetDatabase(job);
        Map<String, SourceTableDetail> detailMap = parseSourceTablesDetail(job);

        try (Connection connection = dorisConn()) {
            for (String sourceTable : job.getSourceTables()) {
                String targetTableName = resolveTargetTableName(job, sourceTable, detailMap);
                registerTable(targetDb, targetTableName, connection, new SourceContext("SYNC", null, null, null, null), null);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("注册 Doris 元数据失败: syncJobId={}", syncJobId, e);
            throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED, "注册 Doris 元数据失败: " + e.getMessage());
        }
    }

    private String resolveTargetDatabase(SyncJobInfo job) {
        return StringUtils.hasText(job.getTargetDatabase()) ? job.getTargetDatabase() : targetDatabase;
    }

    private String resolveTargetTableName(SyncJobInfo job, String sourceTable,
                                          Map<String, SourceTableDetail> detailMap) {
        SourceTableDetail detail = detailMap != null ? detailMap.get(sourceTable) : null;
        if (detail != null && StringUtils.hasText(detail.getTargetTable())) {
            return detail.getTargetTable();
        }
        if (StringUtils.hasText(job.getTargetTable())) {
            return job.getTargetTable();
        }
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String db = sourceDb.replaceAll("[^a-zA-Z0-9_]", "_");
        String table = sourceTable.replaceAll("[^a-zA-Z0-9_]", "_");
        return "sync_" + db + "_" + table;
    }

    private Map<String, SourceTableDetail> parseSourceTablesDetail(SyncJobInfo job) {
        if (!StringUtils.hasText(job.getSourceTablesDetail())) {
            return Map.of();
        }
        try {
            List<SourceTableDetail> details = JSON.parseArray(job.getSourceTablesDetail(), SourceTableDetail.class);
            Map<String, SourceTableDetail> map = new HashMap<>();
            for (SourceTableDetail d : details) {
                if (StringUtils.hasText(d.getSourceTable())) {
                    map.put(d.getSourceTable(), d);
                }
            }
            return map;
        } catch (Exception e) {
            logger.warn("解析 sourceTablesDetail 失败，将使用默认目标表映射: syncJobId={}", job.getId(), e);
            return Map.of();
        }
    }

    /**
     * 单表注册：本地从 Doris information_schema 提取列，治理库写入经 register 端点
     * （findOrCreateTable + refreshColumns + column_count 在服务端一个事务内完成）。
     * 远程写失败 RemoteCalls 降级（不影响执行结果）。
     */
    private void registerTable(String targetDb, String targetTableName, Connection connection,
                               SourceContext ctx, Long operatorId) throws SQLException {
        List<MetadataRegisterColumnDTO> columns = extractColumns(connection, targetDb, targetTableName);
        registerRemote(targetDb, targetTableName, columns, ctx, operatorId);
        logger.info("刷新 BUILTIN_DORIS 元数据表字段: table={}, count={}", targetTableName, columns.size());
    }

    /** 元数据注册远程调用（RemoteCalls 降级：注册失败不影响执行结果，对齐原调用方 try-catch 语义）。 */
    private void registerRemote(String targetDb, String targetTableName, List<MetadataRegisterColumnDTO> columns,
                                SourceContext ctx, Long operatorId) {
        RemoteCalls.execute("governance.metadata.register", () -> {
            MetadataRegisterRequest request = new MetadataRegisterRequest();
            request.setDatabaseName(targetDb);
            request.setTableName(targetTableName);
            request.setColumns(columns);
            if (ctx != null) {
                request.setSourceTaskType(ctx.taskSourceType());
                request.setSourceDagId(ctx.sourceDagId());
                request.setSourceDagName(ctx.sourceDagName());
                request.setSourceNodeId(ctx.sourceNodeId());
                request.setSourceNodeName(ctx.sourceNodeName());
            }
            request.setOperatorId(operatorId);
            metadataWriteApi.register(request);
        });
    }

    public record SourceContext(String taskSourceType, Long sourceDagId, String sourceDagName,
                                String sourceNodeId, String sourceNodeName) {
    }

    private List<MetadataRegisterColumnDTO> extractColumns(Connection connection, String targetDb, String targetTableName) throws SQLException {
        List<MetadataRegisterColumnDTO> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable, column_default, ordinal_position " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetDb);
            ps.setString(2, targetTableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MetadataRegisterColumnDTO column = new MetadataRegisterColumnDTO();
                    column.setColumnName(rs.getString("column_name"));
                    column.setColumnType(rs.getString("data_type"));
                    String nullable = rs.getString("is_nullable");
                    column.setNullable(!"NO".equalsIgnoreCase(nullable));
                    column.setColumnDefault(rs.getString("column_default"));
                    column.setOrdinalPosition(rs.getInt("ordinal_position"));
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    // ============================================
    // Phase 4（Sprint 3）：registerFromSql — 从 SQL 字符串提取表结构并注册
    // 用例：dag_node 为 SQL 节点时，回调里把用户写的 CREATE TABLE / CTAS 解析出来
    //  注册到 metadata_table + metadata_column
    // ============================================

    /**
     * 解析 SQL，提取其中的 CREATE TABLE / DROP TABLE 语句，
     * 把目标库的内置 Doris 表注册到元数据表（跟 register(syncJobId) 行为一致）
     *
     * @param sql        完整 SQL 字符串（支持多条 SQL 用 ; 分隔）
     * @param operatorId 操作人 ID
     * @param ctx        来源上下文（DAG/节点信息）
     * @return 注册的表名列表
     */
    public List<String> registerFromSql(String sql, Long operatorId, SourceContext ctx) {
        if (!StringUtils.hasText(sql)) {
            throw new BusinessException(ErrorCode.SQL_PARSE_FAILED, "SQL 不能为空");
        }

        // 1. 先在 Doris 里执行 SQL（让表真实存在或变更）
        String targetDb = targetDatabase;

        // 2. 解析每条 SQL，分类处理
        List<String> registeredTables = new ArrayList<>();
        String[] statements = splitSqlStatements(sql);
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try {
                net.sf.jsqlparser.statement.Statement parsed =
                        net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(trimmed);

                if (parsed instanceof net.sf.jsqlparser.statement.create.table.CreateTable) {
                    // CREATE TABLE xxx (col1 type, col2 type, ...) 或 CREATE TABLE xxx AS SELECT (CTAS)
                    // Sprint 3 P2-3：CTAS 也被 JSqlParser 解析为 CreateTable，用 getSelect() != null 判断
                    net.sf.jsqlparser.statement.create.table.CreateTable ct =
                            (net.sf.jsqlparser.statement.create.table.CreateTable) parsed;
                    String tableName = extractTableName(ct.getTable());
                    if (tableName == null) {
                        logger.warn("无法提取 CREATE TABLE 表名: {}", trimmed);
                        continue;
                    }
                    if (ct.getSelect() != null) {
                        logger.info("检测到 CTAS: {} (基于 SELECT)", tableName);
                    }
                    executeAndRegister(targetDb, tableName, operatorId, ctx);
                    registeredTables.add(tableName);
                } else if (parsed instanceof net.sf.jsqlparser.statement.insert.Insert) {
                    // INSERT INTO xxx：目标表已注册则刷新元数据（最近写入时间/列结构）
                    net.sf.jsqlparser.statement.insert.Insert insert =
                            (net.sf.jsqlparser.statement.insert.Insert) parsed;
                    String tableName = extractTableName(insert.getTable());
                    if (tableName == null) {
                        logger.warn("无法提取 INSERT 表名: {}", trimmed);
                        continue;
                    }
                    if (refreshIfExists(targetDb, tableName, operatorId, ctx)) {
                        registeredTables.add(tableName);
                    }
                } else if (parsed instanceof net.sf.jsqlparser.statement.alter.Alter) {
                    // ALTER TABLE xxx：刷新列结构
                    net.sf.jsqlparser.statement.alter.Alter alter =
                            (net.sf.jsqlparser.statement.alter.Alter) parsed;
                    String tableName = extractTableName(alter.getTable());
                    if (tableName == null) {
                        logger.warn("无法提取 ALTER TABLE 表名: {}", trimmed);
                        continue;
                    }
                    if (refreshIfExists(targetDb, tableName, operatorId, ctx)) {
                        registeredTables.add(tableName);
                    }
                } else if (parsed instanceof net.sf.jsqlparser.statement.drop.Drop) {
                    // DROP TABLE xxx：从元数据管理中移除
                    net.sf.jsqlparser.statement.drop.Drop drop =
                            (net.sf.jsqlparser.statement.drop.Drop) parsed;
                    String tableName = extractTableName(drop.getName());
                    if (tableName == null) {
                        logger.warn("无法提取 DROP TABLE 表名: {}", trimmed);
                        continue;
                    }
                    removeIfExists(targetDb, tableName);
                } else {
                    // 其他语句（UPDATE/DELETE/SELECT）不参与元数据注册
                    logger.debug("非结构变更语句，跳过元数据注册: {}", trimmed);
                }
            } catch (net.sf.jsqlparser.JSQLParserException e) {
                // 解析失败的非 DDL 语句直接忽略（不抛异常，让数据执行继续）
                logger.debug("SQL 解析失败（非 DDL 跳过）: {}", trimmed, e);
            }
        }
        return registeredTables;
    }

    /**
     * 把 schema-qualified 表名拆分为 db + table。
     * 如果表名本身包含 schema，优先使用表名里的 schema；否则回退到 defaultDb。
     */
    private DbTable resolveDbAndTable(String defaultDb, String tableName) {
        if (tableName != null && tableName.contains(".")) {
            int idx = tableName.indexOf('.');
            return new DbTable(tableName.substring(0, idx), tableName.substring(idx + 1));
        }
        return new DbTable(defaultDb, tableName);
    }

    private record DbTable(String db, String table) {
    }

    /**
     * 注册单个表到元数据（含建表 / 刷新列）
     */
    private void executeAndRegister(String targetDb, String tableName, Long operatorId, SourceContext ctx) {
        DbTable dbTable = resolveDbAndTable(targetDb, tableName);
        String db = dbTable.db();
        String table = dbTable.table();
        try (Connection conn = dorisConn()) {
            // 先确保表真实存在（外部系统已执行过 SQL；这里做幂等保护）
            if (!tableExists(conn, db, table)) {
                logger.warn("表 {}.{} 在 Doris 中不存在，元数据无法注册", db, table);
                return;
            }
            SourceContext effectiveCtx = ctx != null ? ctx : new SourceContext("SQL", null, null, null, null);
            List<MetadataRegisterColumnDTO> columns = extractColumns(conn, db, table);
            registerRemote(db, table, columns, effectiveCtx, operatorId);
            logger.info("从 SQL 注册元数据: db={}, table={}, cols={}", db, table, columns.size());
        } catch (Exception e) {
            logger.error("从 SQL 注册元数据失败: db={}, table={}", db, table, e);
            throw new BusinessException(ErrorCode.METADATA_REGISTRATION_FAILED,
                    "元数据注册失败: " + e.getMessage());
        }
    }

    /**
     * 表已存在时刷新元数据（用于 INSERT/ALTER）。Doris 中不存在则静默跳过。
     * 治理库侧 refresh-if-exists 端点：元数据表存在才刷新列结构 + column_count，不存在静默跳过。
     * 远程写失败 RemoteCalls 降级（不影响执行结果）。
     */
    private boolean refreshIfExists(String targetDb, String tableName, Long operatorId, SourceContext ctx) {
        DbTable dbTable = resolveDbAndTable(targetDb, tableName);
        String db = dbTable.db();
        String table = dbTable.table();
        try (Connection conn = dorisConn()) {
            if (!tableExists(conn, db, table)) {
                logger.debug("表 {}.{} 不存在，跳过元数据刷新", db, table);
                return false;
            }
            List<MetadataRegisterColumnDTO> columns = extractColumns(conn, db, table);
            RemoteCalls.execute("governance.metadata.refresh-if-exists", () -> {
                MetadataRefreshIfExistsRequest request = new MetadataRefreshIfExistsRequest();
                request.setDatabaseName(db);
                request.setTableName(table);
                request.setColumns(columns);
                request.setColumnCount(columns.size());
                request.setOperatorId(operatorId);
                metadataWriteApi.refreshIfExists(request);
            });
            logger.info("从 SQL 刷新元数据: db={}, table={}, cols={}", db, table, columns.size());
            return true;
        } catch (Exception e) {
            logger.error("从 SQL 刷新元数据失败: db={}, table={}", db, table, e);
            throw new BusinessException(ErrorCode.METADATA_REGISTRATION_FAILED,
                    "元数据刷新失败: " + e.getMessage());
        }
    }

    /**
     * 表存在时从元数据移除（用于 DROP TABLE）。不存在则静默跳过（服务端幂等）。
     * 远程写失败 RemoteCalls 降级（不影响执行结果）。
     */
    private void removeIfExists(String targetDb, String tableName) {
        DbTable dbTable = resolveDbAndTable(targetDb, tableName);
        String db = dbTable.db();
        String table = dbTable.table();
        RemoteCalls.execute("governance.metadata.remove", () -> {
            MetadataRemoveRequest request = new MetadataRemoveRequest();
            request.setDatabaseName(db);
            request.setTableName(table);
            metadataWriteApi.remove(request);
        });
        logger.info("从 SQL 删除元数据: db={}, table={}", db, table);
    }

    private boolean tableExists(Connection conn, String db, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ? LIMIT 1")) {
            ps.setString(1, db);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 简单 SQL 切分（按 ; 分隔，忽略字符串内的 ;）
     */
    private String[] splitSqlStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
                current.append(c);
            } else if (c == ';' && !inString) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result.toArray(new String[0]);
    }

    private String extractTableName(net.sf.jsqlparser.schema.Table table) {
        if (table == null) return null;
        if (table.getSchemaName() != null) {
            return table.getSchemaName() + "." + table.getName();
        }
        return table.getName();
    }

    /**
     * Phase 4（Sprint 3）：从 Python 节点输出表注册 Doris 元数据。
     * 表名由 Python 脚本上报，已在 Doris 中真实存在，本方法做幂等注册/刷新。
     */
    public void registerFromPython(String tableName, Long dagId, String dagName,
                                   String nodeId, String nodeName, Long executionId) {
        if (!StringUtils.hasText(tableName)) {
            return;
        }
        String targetDb = targetDatabase;
        try (Connection conn = dorisConn()) {
            if (!tableExists(conn, targetDb, tableName)) {
                logger.warn("Python 输出表 {}.{} 在 Doris 中不存在，跳过元数据注册", targetDb, tableName);
                return;
            }
            SourceContext ctx = new SourceContext("PYTHON", dagId, dagName, nodeId, nodeName);
            Long operatorId = currentUserId();
            List<MetadataRegisterColumnDTO> columns = extractColumns(conn, targetDb, tableName);
            registerRemote(targetDb, tableName, columns, ctx, operatorId);
            logger.info("从 Python 注册元数据: db={}, table={}, cols={}", targetDb, tableName, columns.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("从 Python 注册元数据失败: table={}", tableName, e);
            throw new BusinessException(ErrorCode.METADATA_REGISTRATION_FAILED,
                    "元数据注册失败: " + e.getMessage());
        }
    }

    private long currentUserId() {
        try {
            return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

}
