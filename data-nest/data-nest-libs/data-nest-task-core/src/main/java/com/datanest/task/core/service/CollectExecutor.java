package com.datanest.task.core.service;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.common.constant.DataSourceStatus;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.governance.api.CollectWriteApi;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.dto.CollectDetectDeletedTablesRequest;
import com.datanest.governance.api.dto.CollectHistoryCreateRequest;
import com.datanest.governance.api.dto.CollectHistoryFinishRequest;
import com.datanest.governance.api.dto.CollectHistoryInfoDTO;
import com.datanest.governance.api.dto.CollectLogAppendRequest;
import com.datanest.governance.api.dto.CollectTaskInfoDTO;
import com.datanest.governance.api.dto.CollectTaskMarkStatusRequest;
import com.datanest.governance.api.dto.CollectUpsertColumnsRequest;
import com.datanest.governance.api.dto.CollectUpsertTableRequest;
import com.datanest.governance.api.dto.DetectDeletedResultDTO;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import com.datanest.governance.api.dto.UpsertColumnsResultDTO;
import com.datanest.governance.api.dto.UpsertTableResultDTO;
import com.datanest.task.core.collect.ColumnMetadata;
import com.datanest.task.core.collect.ExtractorFactory;
import com.datanest.task.core.collect.MetadataExtractor;
import com.datanest.task.core.collect.TableMetadata;
import com.datanest.common.constant.AlertConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据采集任务执行核心，供 data-nest-worker 调用。
 * 本身不依赖 XXL-JOB 注解，保持 task-core 为纯库。
 * <p>
 * 微服务化 4.2：collect_task/collect_history/collect_execution_log/collect_change_detail/
 * metadata_table/metadata_column 的读写全部改为经 {@link CollectWriteApi} Feign 调 app-governance；
 * 变更明细由服务端在 upsert/detect 的 diff 逻辑里落库并返回计数，本类只做抽取与统计累加。
 * 容错红线：任务读取/mark-running/history init 失败 fail-fast；执行中写
 * （upsert/detect/日志/变更）与收尾（finish/mark-status）RemoteCalls 降级
 * （采集渐进落库语义不变，失败靠对账兜底）。
 */
@Service
public class CollectExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CollectExecutor.class);

    /** 执行日志缓冲批量刷写阈值（每 50 条 flush 一次，结束再 flush） */
    private static final int LOG_FLUSH_THRESHOLD = 50;

    private final CollectWriteApi collectWriteApi;
    private final GovernanceObjectApi governanceObjectApi;
    private final EngineeringDatasourceApi datasourceApi;
    private final ExtractorFactory extractorFactory;
    private final AlertApi alertApi;

    public CollectExecutor(CollectWriteApi collectWriteApi,
                           GovernanceObjectApi governanceObjectApi,
                           EngineeringDatasourceApi datasourceApi,
                           ExtractorFactory extractorFactory,
                           AlertApi alertApi) {
        this.collectWriteApi = collectWriteApi;
        this.governanceObjectApi = governanceObjectApi;
        this.datasourceApi = datasourceApi;
        this.extractorFactory = extractorFactory;
        this.alertApi = alertApi;
    }

    public void execute(String param) {
        logger.info("CollectExecutor 开始执行，param={}", param);
        Long taskId = parseTaskId(param);
        String triggerType = parseTriggerType(param);
        if (taskId == null) {
            logger.error("CollectExecutor 参数无效，缺少任务ID: param={}", param);
            throw new IllegalArgumentException("缺少任务ID参数");
        }
        runTask(taskId, triggerType);
    }

    private Long parseTaskId(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(param.split(",")[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String parseTriggerType(String param) {
        if (param == null || param.isBlank()) {
            return TaskTriggerType.CRON.getCode();
        }
        String[] parts = param.split(",");
        return parts.length > 1 ? parts[1].trim() : TaskTriggerType.CRON.getCode();
    }

    // 采集为渐进式落库，失败记录即时生效，不使用方法级事务（远程端点逐条提交）。
    public void runTask(Long taskId, String triggerType) {
        logger.info("runTask 开始执行，taskId={}，triggerType={}", taskId, triggerType);
        // 执行开始 fail-fast：任务读不到不启动采集
        CollectTaskInfoDTO task = getTaskOrThrow(taskId);
        // 设置运行中状态（fail-fast：标记不上不跑"无登记执行"）
        markTaskStatusOrThrow(taskId, ExecutionStatus.RUNNING.getCode(), null, null);
        logger.info("runTask 任务状态已更新为 RUNNING: taskId={}", taskId);

        // 经 engineering 服务 Feign 读取数据源连接（fail-fast，不走 RemoteCalls 降级：
        // 连接读不到不启动采集，记 FAILED 历史后抛出）
        DataSourceInfo ds = getDatasourceInfo(task.getDatasourceId());
        if (ds == null) {
            logger.error("runTask 数据源不存在: taskId={}, datasourceId={}", taskId, task.getDatasourceId());
            failTask(task, triggerType, "数据源不存在: " + task.getDatasourceId());
            return;
        }
        if (!DataSourceStatus.NORMAL.getCode().equals(ds.getStatus())) {
            logger.error("runTask 数据源状态异常: taskId={}, datasourceId={}, status={}", taskId, task.getDatasourceId(), ds.getStatus());
            failTask(task, triggerType, "数据源状态异常，无法采集: " + ds.getStatus());
            return;
        }

        logger.info("runTask 准备初始化历史记录，taskId={}", taskId);
        Long historyId = createHistoryOrThrow(task, triggerType);
        logger.info("runTask 历史记录已初始化，historyId={}", historyId);
        LogBuffer logBuffer = new LogBuffer(historyId);
        List<String> scope = task.getScope() != null && !task.getScope().isEmpty()
                ? task.getScope()
                : Collections.singletonList(null);

        int dbCount = 0;
        int tableCount = 0;
        int columnCount = 0;
        int addedTables = 0;
        int updatedTables = 0;
        int deletedTables = 0;
        int addedColumns = 0;
        int updatedColumns = 0;
        int deletedColumns = 0;
        String errorMessage = null;
        String lastStatus = ExecutionStatus.SUCCESS.getCode();
        // 手动停止标记：停止后仍需走收尾（保留 TERMINATED 终态并补统计），故不能靠异常跳出
        boolean terminated = false;

        MetadataExtractor extractor = extractorFactory.getExtractor(ds.getType());
        try {
            logBuffer.log("INFO", "开始采集任务：" + task.getName() + "，数据源：" + ds.getName());
            for (String schema : scope) {
                // 协作式停止：手动停止接口会把历史状态置为 TERMINATED，每轮迭代前重查以便尽快退出
                if (isTerminated(historyId)) {
                    terminated = true;
                    break;
                }
                List<TableMetadata> tables = extractor.extractTables(ds, schema);
                dbCount++;
                logBuffer.log("INFO", "采集到 " + tables.size() + " 张表，范围：" + (schema == null ? "默认" : schema));
                Set<String> collectedTableNames = tables.stream()
                        .map(TableMetadata::getTableName)
                        .collect(Collectors.toSet());
                for (TableMetadata table : tables) {
                    if (isTerminated(historyId)) {
                        terminated = true;
                        break;
                    }
                    // 执行中写失败 RemoteCalls 降级：单表 upsert 失败记 error 并跳过本表，不中断整体采集
                    UpsertTableResultDTO tableResult = upsertTableRemote(ds, table, historyId);
                    if (tableResult == null || tableResult.getTableId() == null) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(tableResult.getIsNew())) addedTables++;
                    if (Boolean.TRUE.equals(tableResult.getChanged())) updatedTables++;
                    tableCount++;
                    UpsertColumnsResultDTO colResult = upsertColumnsRemote(tableResult.getTableId(), table,
                            historyId, Boolean.TRUE.equals(tableResult.getIsNew()));
                    if (colResult != null) {
                        // 复活字段对齐原语义计入 added
                        addedColumns += nz(colResult.getAddedCount()) + nz(colResult.getResurrectedCount());
                        updatedColumns += nz(colResult.getUpdatedCount());
                        deletedColumns += nz(colResult.getDeletedCount());
                    }
                    columnCount += table.getColumns().size();
                }
                if (terminated) {
                    // 停止时跳过删除检测：表清单未采完，若继续会把未采集的表误判为已删除
                    break;
                }
                DetectDeletedResultDTO detectResult = detectDeletedTablesRemote(ds, tables, collectedTableNames, historyId);
                if (detectResult != null) {
                    deletedTables += nz(detectResult.getDeletedTableCount());
                }
            }
            if (terminated) {
                lastStatus = ExecutionStatus.TERMINATED.getCode();
                logBuffer.log("INFO", "手动停止，已采集部分保留");
            } else {
                logBuffer.log("INFO", "采集完成：库/表/字段 = " + dbCount + "/" + tableCount + "/" + columnCount
                        + "，新增/修改/删除表 = " + addedTables + "/" + updatedTables + "/" + deletedTables
                        + "，新增/修改/删除字段 = " + addedColumns + "/" + updatedColumns + "/" + deletedColumns);
            }
        } catch (Exception e) {
            logger.error("采集任务执行失败: taskId={}", taskId, e);
            errorMessage = e.getMessage();
            lastStatus = ExecutionStatus.FAILED.getCode();
            logBuffer.log("ERROR", "采集失败：" + errorMessage);
        }

        // 收尾前最后确认一次停止状态：停止请求若恰好落在循环最后一次检查之后，
        // 这里兜底，避免 finishHistory/updateTaskStatus 把 TERMINATED 覆盖回 SUCCESS
        if (!terminated && ExecutionStatus.SUCCESS.getCode().equals(lastStatus) && isTerminated(historyId)) {
            terminated = true;
            lastStatus = ExecutionStatus.TERMINATED.getCode();
            logBuffer.log("INFO", "手动停止，已采集部分保留");
        }

        // 停止分支同样走收尾：lastStatus 为 TERMINATED，finishHistory/updateTaskStatus 只会保持终态并补统计
        logBuffer.flush();
        finishHistory(historyId, tableCount, columnCount, dbCount, addedTables, updatedTables, deletedTables,
                addedColumns, updatedColumns, deletedColumns, errorMessage, lastStatus);
        markTaskStatus(taskId, lastStatus, historyId, LocalDateTime.now());

        // Sprint 5：采集任务成功/失败告警（按 alert_rule 配置，经 alert-service 远程触发；手动停止不发告警）
        if (ExecutionStatus.SUCCESS.getCode().equals(lastStatus)) {
            fireAlert("COLLECT_TASK", taskId, "SUCCESS",
                    "采集完成：表 " + tableCount + "，字段 " + columnCount);
            // Sprint 8：采集任务成功后触发绑定的质量任务自动检查（失败不影响采集结果）
            triggerQualityOnSuccess(AlertConstants.OBJECT_TYPE_COLLECT_TASK, taskId);
        } else if (ExecutionStatus.FAILED.getCode().equals(lastStatus)) {
            fireAlert("COLLECT_TASK", taskId, "FAILURE", errorMessage);
        }

        if (ExecutionStatus.FAILED.getCode().equals(lastStatus)) {
            throw new RuntimeException(errorMessage);
        }
    }

    /**
     * 经 alert-service 远程触发告警（Feign）。
     * 失败经 RemoteCalls 降级（warn + 计数）按「未发送」处理，不影响主执行流程（最终一致）。
     *
     * @return 是否发送成功（Result 拆信封；异常按 false）
     */
    private boolean fireAlert(String objectType, Long objectId, String alertType, String detail) {
        return RemoteCalls.execute("alert.fire", () -> {
            AlertFireRequest request = new AlertFireRequest();
            request.setObjectType(objectType);
            request.setObjectId(objectId);
            request.setAlertType(alertType);
            request.setDetail(detail);
            var result = alertApi.fire(request);
            return result != null && Boolean.TRUE.equals(result.data());
        }, false);
    }

    /**
     * 触发绑定该对象的质量任务自动检查（经 governance 批量端点，RemoteCalls 降级，失败不影响采集结果）。
     */
    private void triggerQualityOnSuccess(String objectType, Long objectId) {
        RemoteCalls.execute("governance.quality.auto-trigger", () -> {
            QualityAutoTriggerBatchRequest request = new QualityAutoTriggerBatchRequest();
            request.setObjectType(objectType);
            request.setObjectIds(List.of(objectId));
            governanceObjectApi.qualityAutoTriggerBatch(request);
        });
    }

    // ==================== 执行开始处：fail-fast ====================

    /** 读取采集任务定义，读不到（含熔断降级返回空）按「任务不存在」fail-fast。 */
    private CollectTaskInfoDTO getTaskOrThrow(Long taskId) {
        Result<CollectTaskInfoDTO> result = collectWriteApi.getTask(taskId);
        CollectTaskInfoDTO task = result == null ? null : result.data();
        if (task == null) {
            logger.error("runTask 任务不存在: taskId={}", taskId);
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    /** 执行开始的状态回写（置 RUNNING），失败 fail-fast。 */
    private void markTaskStatusOrThrow(Long taskId, String status, Long lastHistoryId, LocalDateTime lastExecuteTime) {
        CollectTaskMarkStatusRequest request = new CollectTaskMarkStatusRequest();
        request.setStatus(status);
        request.setLastHistoryId(lastHistoryId);
        request.setLastExecuteTime(lastExecuteTime == null ? null : formatTime(lastExecuteTime));
        Result<Void> result = collectWriteApi.markTaskStatus(taskId, request);
        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "采集任务状态回写失败（governance 不可达）: taskId=" + taskId);
        }
    }

    /** 初始化采集历史（RUNNING，统计列清零），失败 fail-fast。 */
    private Long createHistoryOrThrow(CollectTaskInfoDTO task, String triggerType) {
        CollectHistoryCreateRequest request = new CollectHistoryCreateRequest();
        request.setTaskId(task.getId());
        request.setTaskName(task.getName());
        request.setDatasourceId(task.getDatasourceId());
        request.setTriggerType(triggerType);
        Result<Long> result = collectWriteApi.createHistory(request);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "创建采集历史失败（governance 不可达）: taskId=" + task.getId());
        }
        return result.data();
    }

    // ==================== 执行中/收尾写：RemoteCalls 降级 ====================

    /**
     * 元数据表 upsert（变更明细服务端落库，计数随响应返回）。
     * 失败降级返回 null，调用方记 error 后跳过本表（不中断整体采集）。
     */
    private UpsertTableResultDTO upsertTableRemote(DataSourceInfo ds, TableMetadata table, Long historyId) {
        return RemoteCalls.execute("governance.collect.upsert-table", () -> {
            CollectUpsertTableRequest request = new CollectUpsertTableRequest();
            request.setDatasourceId(ds.getId());
            request.setDatabaseName(table.getDatabaseName());
            request.setSchemaName(table.getSchemaName());
            request.setTableName(table.getTableName());
            request.setTableComment(table.getTableComment());
            request.setCollectHistoryId(historyId);
            Result<UpsertTableResultDTO> result = collectWriteApi.upsertTable(request);
            return result == null ? null : result.data();
        }, null);
    }

    /**
     * 元数据字段 diff upsert（变更明细服务端落库，计数随响应返回）。
     * 失败降级返回 null（本表字段统计不计入，不中断整体采集）。
     */
    private UpsertColumnsResultDTO upsertColumnsRemote(Long tableId, TableMetadata table, Long historyId, boolean tableIsNew) {
        return RemoteCalls.execute("governance.collect.upsert-columns", () -> {
            CollectUpsertColumnsRequest request = new CollectUpsertColumnsRequest();
            request.setTableId(tableId);
            request.setCollectHistoryId(historyId);
            request.setDatabaseName(table.getDatabaseName());
            request.setSchemaName(table.getSchemaName());
            request.setTableName(table.getTableName());
            request.setTableIsNew(tableIsNew);
            List<CollectUpsertColumnsRequest.ColumnItem> columns = new ArrayList<>(table.getColumns().size());
            for (ColumnMetadata cm : table.getColumns()) {
                CollectUpsertColumnsRequest.ColumnItem item = new CollectUpsertColumnsRequest.ColumnItem();
                item.setColumnName(cm.getColumnName());
                item.setDataType(cm.getDataType());
                item.setColumnComment(cm.getColumnComment());
                item.setOrdinalPosition(cm.getOrdinalPosition());
                item.setNullable(cm.getNullable());
                item.setColumnDefault(cm.getColumnDefault());
                columns.add(item);
            }
            request.setColumns(columns);
            Result<UpsertColumnsResultDTO> result = collectWriteApi.upsertColumns(request);
            return result == null ? null : result.data();
        }, null);
    }

    /**
     * 删除表检测（变更明细服务端落库，计数随响应返回）。
     * 失败降级返回 null（本次不计删除统计，不中断整体采集）。
     */
    private DetectDeletedResultDTO detectDeletedTablesRemote(DataSourceInfo ds, List<TableMetadata> collectedTables,
                                                             Set<String> collectedTableNames, Long historyId) {
        if (collectedTables.isEmpty()) {
            // 对齐原语义：清单为空不做删除检测，避免清单未采完误判删除
            return null;
        }
        TableMetadata first = collectedTables.get(0);
        return RemoteCalls.execute("governance.collect.detect-deleted-tables", () -> {
            CollectDetectDeletedTablesRequest request = new CollectDetectDeletedTablesRequest();
            request.setDatasourceId(ds.getId());
            request.setDatabaseName(first.getDatabaseName());
            request.setSchemaName(first.getSchemaName());
            request.setCollectHistoryId(historyId);
            request.setCurrentTableNames(new ArrayList<>(collectedTableNames));
            Result<DetectDeletedResultDTO> result = collectWriteApi.detectDeletedTables(request);
            return result == null ? null : result.data();
        }, null);
    }

    /** 收尾采集历史（终态 + 统计列），失败 RemoteCalls 降级（靠对账兜底）。 */
    private void finishHistory(Long historyId, int tableCount, int columnCount, int dbCount,
                               int addedTables, int updatedTables, int deletedTables,
                               int addedColumns, int updatedColumns, int deletedColumns,
                               String errorMessage, String status) {
        RemoteCalls.execute("governance.collect.finish-history", () -> {
            CollectHistoryFinishRequest request = new CollectHistoryFinishRequest();
            request.setStatus(status);
            request.setErrorMessage(errorMessage);
            // endedAt/durationMs 留空由服务端兜底（now / startedAt→endedAt）
            request.setTableCount(tableCount);
            request.setColumnCount(columnCount);
            request.setDbCount(dbCount);
            request.setAddedTableCount(addedTables);
            request.setUpdatedTableCount(updatedTables);
            request.setDeletedTableCount(deletedTables);
            request.setAddedColumnCount(addedColumns);
            request.setUpdatedColumnCount(updatedColumns);
            request.setDeletedColumnCount(deletedColumns);
            collectWriteApi.finishHistory(historyId, request);
        });
    }

    /** 收尾任务状态回写（终态 + lastHistoryId + lastExecuteTime），失败 RemoteCalls 降级。 */
    private void markTaskStatus(Long taskId, String status, Long lastHistoryId, LocalDateTime lastExecuteTime) {
        RemoteCalls.execute("governance.collect.mark-task-status", () -> {
            CollectTaskMarkStatusRequest request = new CollectTaskMarkStatusRequest();
            request.setStatus(status);
            request.setLastHistoryId(lastHistoryId);
            request.setLastExecuteTime(lastExecuteTime == null ? null : formatTime(lastExecuteTime));
            collectWriteApi.markTaskStatus(taskId, request);
        });
    }

    // 手动停止通过 DB 状态传递（不走 XXL-JOB kill），执行器循环中重查实现协作式退出。
    // 远程读取失败按未停止处理（与本地 selectById 返回 null 语义一致）。
    private boolean isTerminated(Long historyId) {
        CollectHistoryInfoDTO current = RemoteCalls.execute("governance.collect.get-history", () -> {
            Result<CollectHistoryInfoDTO> result = collectWriteApi.getHistory(historyId);
            return result == null ? null : result.data();
        }, null);
        return current != null && ExecutionStatus.TERMINATED.getCode().equals(current.getStatus());
    }

    private void failTask(CollectTaskInfoDTO task, String triggerType, String message) {
        try {
            Long historyId = createHistoryOrThrow(task, triggerType);
            finishHistory(historyId, 0, 0, 0, 0, 0, 0, 0, 0, 0, message, ExecutionStatus.FAILED.getCode());
            markTaskStatus(task.getId(), ExecutionStatus.FAILED.getCode(), historyId, LocalDateTime.now());
        } catch (Exception e) {
            logger.error("failTask 收尾回写失败: taskId={}", task.getId(), e);
        }
        throw new RuntimeException(message);
    }

    /**
     * 经 Feign 读取数据源连接信息（仅作连接参数载体传给采集抽取器，不落库）。
     * 返回 null 表示读不到（含熔断降级返回空），由调用方按「数据源不存在」fail-fast。
     */
    private DataSourceInfo getDatasourceInfo(Long datasourceId) {
        Result<DataSourceInfo> result = datasourceApi.getById(datasourceId);
        return result == null ? null : result.data();
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 执行日志缓冲：攒批经 logs:append 远程写入（每 50 条 flush + 结束 flush），
     * 失败 RemoteCalls 降级丢弃本批（日志不阻断采集主流程）。
     */
    private class LogBuffer {

        private final Long historyId;
        private final List<CollectLogAppendRequest.Entry> buffer = new ArrayList<>();

        LogBuffer(Long historyId) {
            this.historyId = historyId;
        }

        void log(String level, String message) {
            CollectLogAppendRequest.Entry entry = new CollectLogAppendRequest.Entry();
            entry.setLevel(level);
            entry.setMessage(message);
            buffer.add(entry);
            if (buffer.size() >= LOG_FLUSH_THRESHOLD) {
                flush();
            }
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            List<CollectLogAppendRequest.Entry> batch = new ArrayList<>(buffer);
            buffer.clear();
            RemoteCalls.execute("governance.collect.logs-append", () -> {
                CollectLogAppendRequest request = new CollectLogAppendRequest();
                request.setEntries(batch);
                collectWriteApi.appendLogs(historyId, request);
            });
        }
    }
}
