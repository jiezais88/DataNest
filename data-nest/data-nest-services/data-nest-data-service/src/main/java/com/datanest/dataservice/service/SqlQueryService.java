package com.datanest.dataservice.service;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.dataservice.dto.SqlDatasourceDTO;
import com.datanest.dataservice.dto.SqlExecuteRequest;
import com.datanest.dataservice.dto.SqlExecuteResult;
import com.datanest.dataservice.entity.SqlQueryHistory;
import com.datanest.dataservice.mapper.SqlQueryHistoryMapper;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import com.datanest.task.core.service.DorisSqlExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SQL 终端执行服务（Sprint 10 F1）。
 * <p>
 * 流程（对齐技术文档 §4.1）：JSqlParser 语法级只读校验 + 表集合提取 → governance 批量查敏感度
 * （fail-closed：不可达拒绝）→ 数据源路由（内置 Doris=-1 走 DorisSqlExecutor；外部走
 * EngineeringDatasourceApi.getById + 解密 + ExternalSqlExecutor）→ 结果 ≤1000 行 + durationMs →
 * 异步写 sql_query_history（不阻塞返回）。
 */
@Service
public class SqlQueryService {

    private static final Logger logger = LoggerFactory.getLogger(SqlQueryService.class);

    /** 内置 Doris 数据源 ID（对齐治理侧 BUILTIN_DORIS_DATASOURCE_ID=-1） */
    public static final long BUILTIN_DORIS_DATASOURCE_ID = -1L;

    private static final String CONFIDENTIAL = "CONFIDENTIAL";

    /** 单次查询超时上限（秒），防用户传超大值占用资源 */
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /** 查询历史异步写线程池（虚拟线程语义，队列背压保护） */
    private static final ExecutorService HISTORY_WRITER = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500),
            Thread.ofVirtual().name("sql-history-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final ReadOnlySqlValidator readOnlySqlValidator;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final ExternalSqlExecutor externalSqlExecutor;
    private final EngineeringDatasourceApi datasourceApi;
    private final GovernanceMetadataApi governanceMetadataApi;
    private final EncryptionConfig encryptionConfig;
    private final SqlQueryHistoryMapper historyMapper;

    @Value("${datanest.dataservice.sql.query-timeout-seconds:60}")
    private int queryTimeoutSeconds;

    public SqlQueryService(ReadOnlySqlValidator readOnlySqlValidator, DorisSqlExecutor dorisSqlExecutor,
                           ExternalSqlExecutor externalSqlExecutor, EngineeringDatasourceApi datasourceApi,
                           GovernanceMetadataApi governanceMetadataApi, EncryptionConfig encryptionConfig,
                           SqlQueryHistoryMapper historyMapper) {
        this.readOnlySqlValidator = readOnlySqlValidator;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.externalSqlExecutor = externalSqlExecutor;
        this.datasourceApi = datasourceApi;
        this.governanceMetadataApi = governanceMetadataApi;
        this.encryptionConfig = encryptionConfig;
        this.historyMapper = historyMapper;
    }

    /**
     * 执行只读 SQL。
     */
    public SqlExecuteResult execute(SqlExecuteRequest request) {
        long start = System.currentTimeMillis();
        int timeout = request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0
                ? Math.min(request.getTimeoutSeconds(), MAX_TIMEOUT_SECONDS) : queryTimeoutSeconds;

        // 1. JSqlParser 语法级只读校验 + 表集合提取（AC-1：INSERT/UPDATE/DDL/注释绕过拦截）
        List<String> tables = readOnlySqlValidator.validateAndExtractTables(request.getSql());

        // 2. 敏感度闸门（fail-closed，AC-11）
        checkSensitivity(request.getDatasourceId(), request.getSql(), tables);

        // 3. 数据源路由执行
        SqlExecuteResult result;
        if (request.getDatasourceId() == BUILTIN_DORIS_DATASOURCE_ID) {
            result = executeOnDoris(request.getSql(), timeout);
        } else {
            result = executeOnExternal(request.getDatasourceId(), request.getSql(), timeout);
        }
        result.setDurationMs(System.currentTimeMillis() - start);

        // 4. 异步写历史（不阻塞返回）
        asyncSaveHistory(request, result);
        return result;
    }

    private SqlExecuteResult executeOnDoris(String sql, int timeout) {
        DorisSqlExecutor.QueryResult qr = dorisSqlExecutor.query(sql, timeout);
        SqlExecuteResult result = new SqlExecuteResult();
        result.setColumns(qr.columns());
        result.setRows(qr.rows());
        result.setTruncated(qr.truncated());
        result.setRowCount(qr.rows().size());
        return result;
    }

    private SqlExecuteResult executeOnExternal(Long datasourceId, String sql, int timeout) {
        DataSourceInfo ds = resolveDatasource(datasourceId);
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        ExternalSqlExecutor.QueryResult qr = externalSqlExecutor.query(
                ds.getType(), ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getSchemaName(),
                ds.getUsername(), password, sql, timeout);
        SqlExecuteResult result = new SqlExecuteResult();
        result.setColumns(qr.columns());
        result.setRows(qr.rows());
        result.setTruncated(qr.truncated());
        result.setRowCount(qr.rows().size());
        return result;
    }

    private DataSourceInfo resolveDatasource(Long datasourceId) {
        var resp = datasourceApi.getById(datasourceId);
        if (resp == null || resp.data() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        DataSourceInfo ds = resp.data();
        // ERROR 状态仍可执行（连接信息有效，仅上次检测异常）；已下线拒绝
        if (com.datanest.common.constant.DataSourceStatus.OFFLINE.getCode().equals(ds.getStatus())) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源已下线");
        }
        return ds;
    }

    /**
     * 表敏感度闸门：SQL 引用的表批量查治理敏感度，命中 CONFIDENTIAL 拦截（9004）。
     * <p>
     * fail-closed（用户已确认，技术文档 §8 Blocker 3）：governance 不可达（契约返回 null）时
     * 拒绝执行并提示「分级服务暂不可用」，避免机密表因治理故障裸奔。
     */
    private void checkSensitivity(Long datasourceId, String sql, List<String> tables) {
        if (tables.isEmpty()) {
            return; // SHOW/DESC 等无表引用语句不校验
        }
        // 表引用可能带库前缀（db.table），批量查询用纯表名去重
        List<String> tableNames = tables.stream()
                .map(t -> t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t)
                .distinct()
                .collect(Collectors.toList());
        String joined = String.join(",", tableNames);

        var resp = governanceMetadataApi.getSensitivity(datasourceId, null, null, joined);
        // fail-closed：契约不可达（null）或治理返回业务失败（code!=200）都拒绝，避免机密表因治理故障裸奔
        if (resp == null || resp.code() != 200) {
            throw new BusinessException(ErrorCode.SENSITIVITY_SERVICE_UNAVAILABLE,
                    "分级服务暂不可用，已阻止本次查询，请稍后重试");
        }
        List<MetadataTableSensitivityDTO> list = resp.data();
        if (list == null || list.isEmpty()) {
            return; // 未打标（默认 PUBLIC）
        }
        Set<String> confidentialTables = list.stream()
                .filter(dto -> CONFIDENTIAL.equals(dto.getSensitivityLevel()))
                .map(MetadataTableSensitivityDTO::getTableName)
                .collect(Collectors.toSet());
        if (!confidentialTables.isEmpty()) {
            throw new BusinessException(ErrorCode.TABLE_SENSITIVE,
                    "SQL 命中机密数据表，禁止查询: " + String.join(", ", confidentialTables));
        }
    }

    /**
     * SQL 终端数据源下拉：内置 Doris（固定首位，datasourceId=-1）+ 状态 NORMAL 的平台数据源。
     * engineering listActive 返回 NORMAL/ERROR，过滤只留 NORMAL（PRD：下拉 = 内置 Doris + NORMAL/ONLINE）。
     */
    public List<SqlDatasourceDTO> listQueryableDatasources() {
        List<SqlDatasourceDTO> list = new ArrayList<>();

        SqlDatasourceDTO builtin = new SqlDatasourceDTO();
        builtin.setId(BUILTIN_DORIS_DATASOURCE_ID);
        builtin.setName("Doris 数仓");
        builtin.setType("DORIS");
        builtin.setBuiltin(true);
        builtin.setDatabaseName(com.datanest.task.core.config.DorisDataSourceConfig.currentDatabase());
        list.add(builtin);

        var resp = datasourceApi.listActive();
        if (resp != null && resp.data() != null) {
            resp.data().stream()
                    .filter(ds -> "NORMAL".equals(ds.getStatus()))
                    .forEach(ds -> {
                        SqlDatasourceDTO dto = new SqlDatasourceDTO();
                        dto.setId(ds.getId());
                        dto.setName(ds.getName());
                        dto.setType(ds.getType());
                        dto.setBuiltin(false);
                        dto.setDatabaseName(ds.getDatabaseName());
                        list.add(dto);
                    });
        }
        return list;
    }

    private void asyncSaveHistory(SqlExecuteRequest request, SqlExecuteResult result) {
        try {
            long userId;
            try {
                userId = StpUtil.getLoginIdAsLong();
            } catch (Exception e) {
                return; // 无登录态（内部场景）不写历史
            }
            long finalUserId = userId;
            SqlQueryHistory history = new SqlQueryHistory();
            history.setUserId(finalUserId);
            history.setDatasourceId(request.getDatasourceId());
            history.setSqlText(request.getSql());
            history.setDurationMs((int) result.getDurationMs());
            history.setRowCount(result.getRowCount());
            CompletableFuture.runAsync(() -> {
                try {
                    historyMapper.insert(history);
                } catch (Exception e) {
                    logger.warn("写入 SQL 查询历史失败: {}", e.getMessage());
                }
            }, HISTORY_WRITER);
        } catch (Exception e) {
            logger.warn("SQL 查询历史异步提交失败: {}", e.getMessage());
        }
    }
}
