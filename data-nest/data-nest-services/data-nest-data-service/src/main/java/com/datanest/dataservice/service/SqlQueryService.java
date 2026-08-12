package com.datanest.dataservice.service;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DorisConstants;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.dataservice.dto.SqlDatasourceDTO;
import com.datanest.dataservice.dto.SqlExecuteRequest;
import com.datanest.dataservice.dto.SqlExecuteResult;
import com.datanest.dataservice.dto.SqlExportRequest;
import com.datanest.dataservice.entity.SqlQueryHistory;
import com.datanest.dataservice.mapper.SqlQueryHistoryMapper;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SQL 终端执行服务（Sprint 10 F1 + F1.1 取消支持）。
 * <p>
 * 流程（对齐技术文档 §4.1）：JSqlParser 语法级只读校验 + 表集合提取 → governance 批量查敏感度
 * （fail-closed：不可达拒绝）→ 数据源路由（内置 Doris=-1 / 外部数据源）→ 结果 ≤1000 行 + durationMs →
 * 异步写 sql_query_history（不阻塞返回）。
 * <p>
 * F1.1：整个校验+执行提交流程在虚拟线程中执行，按 queryId 注册 {@link RunningQuery}
 * （Future + 打开的 Connection），「停止」时 interrupt 线程 + 关闭连接，立即中断 JDBC 阻塞读取。
 */
@Service
public class SqlQueryService {

    private static final Logger logger = LoggerFactory.getLogger(SqlQueryService.class);

    private static final String CONFIDENTIAL = "CONFIDENTIAL";

    /** 单次查询超时上限（秒），防用户传超大值占用资源 */
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /** 查询执行线程池（虚拟线程语义，每查询独立线程，取消可中断） */
    private static final ExecutorService QUERY_EXECUTOR = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** 查询历史异步写线程池（虚拟线程语义，队列背压保护） */
    private static final ExecutorService HISTORY_WRITER = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500),
            Thread.ofVirtual().name("sql-history-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 运行中的查询注册表：queryId -> RunningQuery（「停止」按钮取消用） */
    private final Map<String, RunningQuery> runningQueries = new ConcurrentHashMap<>();

    private final ReadOnlySqlValidator readOnlySqlValidator;
    private final CancelableSqlExecutor cancelableSqlExecutor;
    private final ExternalSqlExecutor externalSqlExecutor;
    private final EngineeringDatasourceApi datasourceApi;
    private final GovernanceMetadataApi governanceMetadataApi;
    private final EncryptionConfig encryptionConfig;
    private final SqlQueryHistoryMapper historyMapper;

    @Value("${datanest.dataservice.sql.query-timeout-seconds:60}")
    private int queryTimeoutSeconds;

    public SqlQueryService(ReadOnlySqlValidator readOnlySqlValidator,
                           CancelableSqlExecutor cancelableSqlExecutor,
                           ExternalSqlExecutor externalSqlExecutor,
                           EngineeringDatasourceApi datasourceApi,
                           GovernanceMetadataApi governanceMetadataApi,
                           EncryptionConfig encryptionConfig,
                           SqlQueryHistoryMapper historyMapper) {
        this.readOnlySqlValidator = readOnlySqlValidator;
        this.cancelableSqlExecutor = cancelableSqlExecutor;
        this.externalSqlExecutor = externalSqlExecutor;
        this.datasourceApi = datasourceApi;
        this.governanceMetadataApi = governanceMetadataApi;
        this.encryptionConfig = encryptionConfig;
        this.historyMapper = historyMapper;
    }

    /**
     * 执行只读 SQL（可取消：请求带 queryId 时注册取消句柄，前端可经 {@link #cancel} 停止）。
     */
    public SqlExecuteResult execute(SqlExecuteRequest request) {
        long start = System.currentTimeMillis();
        int timeout = request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0
                ? Math.min(request.getTimeoutSeconds(), MAX_TIMEOUT_SECONDS) : queryTimeoutSeconds;
        String queryId = request.getQueryId();

        // 在请求线程（Sa-Token 上下文可访问）先取 userId，传入虚拟线程避免 ThreadLocal 丢失
        Long userId = resolveCurrentUserId();

        RunningQuery runningQuery = new RunningQuery();
        Future<SqlExecuteResult> future = QUERY_EXECUTOR.submit(() -> {
            try {
                // 1. JSqlParser 语法级只读校验 + 表集合提取（AC-1：INSERT/UPDATE/DDL/注释绕过拦截）
                List<String> tables = readOnlySqlValidator.validateAndExtractTables(request.getSql());

                // 2. 敏感度闸门（fail-closed，AC-11）——命中机密表抛 9004；否则返回命中数（成功恒 0）
                int confidentialHits = checkSensitivity(request.getDatasourceId(), tables);

                // 3. 数据源路由执行（连接建立即注册到 runningQuery，取消时关闭连接立即中断）
                SqlExecuteResult result = executeQuery(request.getDatasourceId(), request.getSql(), timeout, runningQuery);
                result.setDurationMs((int) (System.currentTimeMillis() - start));
                result.setTableCount(tables.size());
                result.setConfidentialHits(confidentialHits);

                // 4. 异步写历史（不阻塞返回）
                asyncSaveHistory(userId, request, (int) (System.currentTimeMillis() - start), result.getRowCount(), null);
                return result;
            } catch (BusinessException e) {
                // 用户主动停止：cancel 关闭连接导致 JDBC 抛异常（如 Communications link failure）
                // → 统一映射为「查询已被停止」，避免历史/错误提示误报「查询失败」
                if (runningQuery.isCancelled()) {
                    BusinessException stopped = new BusinessException(ErrorCode.SQL_TIMEOUT, "查询已被停止");
                    asyncSaveHistory(userId, request, (int) (System.currentTimeMillis() - start), null, stopped.getMessage());
                    throw stopped;
                }
                // 失败也写历史（含错误信息，供查询历史回显/回填重试）
                asyncSaveHistory(userId, request, (int) (System.currentTimeMillis() - start), null, e.getMessage());
                throw e;
            } catch (Exception e) {
                logger.warn("SQL 查询执行异常: sql={}, error={}", request.getSql(), e.getMessage());
                BusinessException be = new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "查询失败: " + e.getMessage());
                asyncSaveHistory(userId, request, (int) (System.currentTimeMillis() - start), null, be.getMessage());
                throw be;
            }
        });
        runningQuery.setFuture(future);
        if (queryId != null && !queryId.isBlank()) {
            runningQueries.put(queryId, runningQuery);
        }

        try {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SQL_TIMEOUT, "查询已被停止");
            } catch (CancellationException e) {
                throw new BusinessException(ErrorCode.SQL_TIMEOUT, "查询已被停止");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED,
                        cause != null && cause.getMessage() != null ? cause.getMessage() : "查询失败");
            }
        } finally {
            if (queryId != null && !queryId.isBlank()) {
                runningQueries.remove(queryId);
            }
        }
    }

    /**
     * 取消指定 queryId 的查询：中断执行线程 + 关闭已建立的连接（幂等，查无此 id 返回 false）。
     */
    public boolean cancel(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return false;
        }
        RunningQuery runningQuery = runningQueries.get(queryId);
        if (runningQuery == null) {
            return false;
        }
        logger.info("取消 SQL 查询: queryId={}", queryId);
        runningQuery.cancel();
        return true;
    }

    private SqlExecuteResult executeQuery(Long datasourceId, String sql, int timeout, RunningQuery runningQuery) {
        if (datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            CancelableSqlExecutor.QueryResult qr = cancelableSqlExecutor.queryDoris(
                    sql, timeout, runningQuery::setConnection);
            SqlExecuteResult result = new SqlExecuteResult();
            result.setColumns(qr.columns());
            result.setRows(qr.rows());
            result.setTruncated(qr.truncated());
            result.setRowCount(qr.rows().size());
            return result;
        }
        DataSourceInfo ds = resolveDatasource(datasourceId);
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        CancelableSqlExecutor.QueryResult qr = cancelableSqlExecutor.queryExternal(
                ds.getType(), ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getSchemaName(),
                ds.getUsername(), password, sql, timeout, runningQuery::setConnection);
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
     *
     * @return 命中机密表的数量（成功放行时恒为 0，供前端「机密拦截」KPI）
     */
    private int checkSensitivity(Long datasourceId, List<String> tables) {
        if (tables.isEmpty()) {
            return 0; // SHOW/DESC 等无表引用语句不校验
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
            return 0; // 未打标（默认 PUBLIC）
        }
        Set<String> confidentialTables = list.stream()
                .filter(dto -> CONFIDENTIAL.equals(dto.getSensitivityLevel()))
                .map(MetadataTableSensitivityDTO::getTableName)
                .collect(Collectors.toSet());
        if (!confidentialTables.isEmpty()) {
            throw new BusinessException(ErrorCode.TABLE_SENSITIVE,
                    "SQL 命中机密数据表，禁止查询: " + String.join(", ", confidentialTables));
        }
        return 0;
    }

    /**
     * SQL 终端导出（Sprint 10 F1，后端导出）：复用 execute 全链路（只读校验 + 敏感度闸门 + 执行 + 写历史），
     * 结果由 Controller 转为 XLSX/CSV 文件流。行数上限与查询一致（1000）。
     */
    public SqlExecuteResult export(SqlExportRequest request) {
        SqlExecuteRequest exec = new SqlExecuteRequest();
        exec.setDatasourceId(request.getDatasourceId());
        exec.setSql(request.getSql());
        exec.setTimeoutSeconds(request.getTimeoutSeconds());
        return execute(exec);
    }

    /** 数据源显示名（导出文件名用）：内置 Doris → 「Doris 数仓」，平台数据源查下拉映射，查不到用「数据源」 */
    public String datasourceName(Long datasourceId) {
        if (datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return DorisConstants.BUILTIN_DORIS_NAME;
        }
        return listQueryableDatasources().stream()
                .filter(d -> d.getId().equals(datasourceId))
                .map(SqlDatasourceDTO::getName)
                .findFirst()
                .orElse("数据源");
    }

    /**
     * SQL 终端数据源下拉：内置 Doris（固定首位，datasourceId=-1）+ 状态 NORMAL 的平台数据源。
     * engineering listActive 返回 NORMAL/ERROR，过滤只留 NORMAL（PRD：下拉 = 内置 Doris + NORMAL/ONLINE）。
     */
    public List<SqlDatasourceDTO> listQueryableDatasources() {
        List<SqlDatasourceDTO> list = new ArrayList<>();

        SqlDatasourceDTO builtin = new SqlDatasourceDTO();
        builtin.setId(DorisConstants.BUILTIN_DORIS_DATASOURCE_ID);
        builtin.setName(DorisConstants.BUILTIN_DORIS_NAME);
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

    /**
     * 请求线程内解析当前登录用户 id（Sa-Token ThreadLocal 仅请求线程可访问）。
     * 取不到（内部场景/无登录态）返回 null，不写历史。
     */
    private Long resolveCurrentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 异步写查询历史（成功/失败统一走此方法，不阻塞返回）。
     *
     * @param userId       登录用户 id（请求线程已解析，避免虚拟线程 Sa-Token 上下文丢失）
     * @param request      执行请求
     * @param durationMs   耗时毫秒
     * @param rowCount     返回行数（失败为 null）
     * @param errorMessage 错误信息（成功为 null）
     */
    private void asyncSaveHistory(Long userId, SqlExecuteRequest request, int durationMs, Integer rowCount, String errorMessage) {
        try {
            if (userId == null) {
                return; // 无登录态（内部场景）不写历史
            }
            long finalUserId = userId;
            SqlQueryHistory history = new SqlQueryHistory();
            history.setUserId(finalUserId);
            history.setDatasourceId(request.getDatasourceId());
            history.setSqlText(request.getSql());
            history.setDurationMs(durationMs);
            history.setRowCount(rowCount);
            history.setErrorMessage(errorMessage);
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

    /**
     * 运行中查询句柄：Future（interrupt）+ Connection（关闭连接立即中断 JDBC 阻塞读取）。
     */
    static class RunningQuery {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;
        private volatile Connection connection;

        void setFuture(Future<?> future) {
            this.future = future;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void setConnection(Connection connection) {
            this.connection = connection;
            // 竞态：cancel 发生在连接建立前（连接建立后才注册）——建立后立即关闭
            if (cancelled.get() && connection != null) {
                closeQuietly(connection);
            }
        }

        void cancel() {
            cancelled.set(true);
            Future<?> f = future;
            if (f != null) {
                f.cancel(true);
            }
            Connection c = connection;
            if (c != null) {
                closeQuietly(c);
            }
        }

        private static void closeQuietly(Connection c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 连接关闭失败无需处理（Statement 超时兜底）
            }
        }
    }
}
