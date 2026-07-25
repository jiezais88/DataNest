package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.governance.collect.ColumnMetadata;
import com.datanest.governance.collect.ExtractorFactory;
import com.datanest.governance.collect.MetadataExtractor;
import com.datanest.governance.collect.TableMetadata;
import com.datanest.governance.entity.*;
import com.datanest.governance.mapper.*;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CollectExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CollectExecutor.class);

    private final CollectTaskMapper collectTaskMapper;
    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper logMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final ExtractorFactory extractorFactory;

    @XxlJob("collectTaskHandler")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        logger.info("CollectExecutor 开始执行，param={}", param);
        Long taskId = parseTaskId(param);
        String triggerType = parseTriggerType(param);
        if (taskId == null) {
            logger.error("CollectExecutor 参数无效，缺少任务ID: param={}", param);
            XxlJobHelper.handleFail("缺少任务ID参数");
            return;
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
            return "CRON";
        }
        String[] parts = param.split(",");
        return parts.length > 1 ? parts[1].trim() : "CRON";
    }

    @Transactional(rollbackFor = Exception.class)
    public void runTask(Long taskId, String triggerType) {
        logger.info("runTask 开始执行，taskId={}，triggerType={}", taskId, triggerType);
        CollectTask task = collectTaskMapper.selectById(taskId);
        if (task == null) {
            logger.error("runTask 任务不存在: taskId={}", taskId);
            XxlJobHelper.handleFail("任务不存在: " + taskId);
            return;
        }
        if ("PAUSED".equals(task.getStatus())) {
            logger.warn("runTask 任务已暂停，跳过: taskId={}", taskId);
            XxlJobHelper.handleFail("任务已暂停，跳过执行: " + taskId);
            return;
        }

        DataSourceConnection ds = dataSourceConnectionMapper.selectById(task.getDatasourceId());
        if (ds == null) {
            logger.error("runTask 数据源不存在: taskId={}, datasourceId={}", taskId, task.getDatasourceId());
            failTask(task, triggerType, "数据源不存在: " + task.getDatasourceId());
            return;
        }
        if (!"NORMAL".equals(ds.getStatus())) {
            logger.error("runTask 数据源状态异常: taskId={}, datasourceId={}, status={}", taskId, task.getDatasourceId(), ds.getStatus());
            failTask(task, triggerType, "数据源状态异常，无法采集: " + ds.getStatus());
            return;
        }

        logger.info("runTask 准备初始化历史记录，taskId={}", taskId);
        CollectHistory history = initHistory(task, triggerType);
        logger.info("runTask 历史记录已初始化，historyId={}", history.getId());
        List<String> scope = task.getScope() != null && !task.getScope().isEmpty()
                ? task.getScope()
                : Collections.singletonList(null);

        int dbCount = 0;
        int tableCount = 0;
        int columnCount = 0;
        int addedTables = 0;
        int updatedTables = 0;
        int addedColumns = 0;
        int updatedColumns = 0;
        String errorMessage = null;
        String lastStatus = "SUCCESS";

        MetadataExtractor extractor = extractorFactory.getExtractor(ds.getType());
        try {
            log(history, "INFO", "开始采集任务：" + task.getName() + "，数据源：" + ds.getName());
            for (String schema : scope) {
                List<TableMetadata> tables = extractor.extractTables(ds, schema);
                dbCount++;
                log(history, "INFO", "采集到 " + tables.size() + " 张表，范围：" + (schema == null ? "默认" : schema));
                for (TableMetadata table : tables) {
                    TableChange change = upsertTable(task, ds, table, history.getId());
                    if (change.added) addedTables++;
                    if (change.updated) updatedTables++;
                    tableCount++;
                    ColumnChange colChange = upsertColumns(change.tableId, table, history.getId());
                    addedColumns += colChange.added;
                    updatedColumns += colChange.updated;
                    columnCount += table.getColumns().size();
                }
            }
            log(history, "INFO", "采集完成：库/表/字段 = " + dbCount + "/" + tableCount + "/" + columnCount);
        } catch (Exception e) {
            logger.error("采集任务执行失败: taskId={}", taskId, e);
            errorMessage = e.getMessage();
            lastStatus = "FAILED";
            log(history, "ERROR", "采集失败：" + errorMessage);
        }

        finishHistory(history, tableCount, columnCount, dbCount, addedTables, updatedTables,
                addedColumns, updatedColumns, errorMessage, lastStatus);
        updateTaskStatus(task, history, lastStatus);

        if ("FAILED".equals(lastStatus)) {
            XxlJobHelper.handleFail(errorMessage);
        } else {
            XxlJobHelper.handleSuccess();
        }
    }

    private CollectHistory initHistory(CollectTask task, String triggerType) {
        logger.info("initHistory 开始，taskId={}，triggerType={}", task.getId(), triggerType);
        CollectHistory history = new CollectHistory();
        history.setTaskId(task.getId());
        history.setTaskName(task.getName());
        history.setDatasourceId(task.getDatasourceId());
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartedAt(LocalDateTime.now());
        history.setDbCount(0);
        history.setTableCount(0);
        history.setColumnCount(0);
        history.setAddedTableCount(0);
        history.setUpdatedTableCount(0);
        history.setDeletedTableCount(0);
        history.setAddedColumnCount(0);
        history.setUpdatedColumnCount(0);
        history.setDeletedColumnCount(0);
        logger.info("initHistory 准备插入 collect_history，taskId={}", task.getId());
        collectHistoryMapper.insert(history);
        logger.info("initHistory 插入完成，historyId={}", history.getId());
        return history;
    }

    private void log(CollectHistory history, String level, String message) {
        CollectExecutionLog log = new CollectExecutionLog();
        log.setHistoryId(history.getId());
        log.setTaskId(history.getTaskId());
        log.setLevel(level);
        log.setMessage(message);
        logMapper.insert(log);
    }

    private TableChange upsertTable(CollectTask task, DataSourceConnection ds, TableMetadata table, Long historyId) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", ds.getId())
                .eq("database_name", table.getDatabaseName())
                .eq("COALESCE(schema_name, '')", table.getSchemaName() == null ? "" : table.getSchemaName())
                .eq("table_name", table.getTableName());
        MetadataTable existing = metadataTableMapper.selectOne(wrapper);

        if (existing == null) {
            MetadataTable mt = new MetadataTable();
            mt.setDatasourceId(ds.getId());
            mt.setDatabaseName(table.getDatabaseName());
            mt.setSchemaName(table.getSchemaName());
            mt.setTableName(table.getTableName());
            mt.setTableComment(table.getTableComment());
            mt.setLastCollectHistoryId(historyId);
            metadataTableMapper.insert(mt);
            return new TableChange(mt.getId(), true, false);
        } else {
            boolean updated = !Objects.equals(existing.getTableComment(), table.getTableComment());
            if (updated) {
                existing.setTableComment(table.getTableComment());
                existing.setLastCollectHistoryId(historyId);
                metadataTableMapper.updateById(existing);
            }
            return new TableChange(existing.getId(), false, updated);
        }
    }

    private ColumnChange upsertColumns(Long tableId, TableMetadata table, Long historyId) {
        List<MetadataColumn> existingColumns = metadataColumnMapper.selectList(
                new QueryWrapper<MetadataColumn>().eq("table_id", tableId));
        Map<String, MetadataColumn> existingMap = existingColumns.stream()
                .collect(Collectors.toMap(MetadataColumn::getColumnName, c -> c, (a, b) -> a));

        int added = 0;
        int updated = 0;
        for (ColumnMetadata cm : table.getColumns()) {
            MetadataColumn existing = existingMap.get(cm.getColumnName());
            if (existing == null) {
                MetadataColumn col = new MetadataColumn();
                col.setTableId(tableId);
                col.setColumnName(cm.getColumnName());
                col.setColumnComment(cm.getColumnComment());
                col.setDataType(cm.getDataType());
                col.setOrdinalPosition(cm.getOrdinalPosition());
                col.setNullable(cm.getNullable());
                col.setColumnDefault(cm.getColumnDefault());
                col.setLastCollectHistoryId(historyId);
                metadataColumnMapper.insert(col);
                added++;
            } else {
                boolean changed = !Objects.equals(existing.getDataType(), cm.getDataType())
                        || !Objects.equals(existing.getColumnComment(), cm.getColumnComment())
                        || !Objects.equals(existing.getOrdinalPosition(), cm.getOrdinalPosition())
                        || !Objects.equals(existing.getNullable(), cm.getNullable())
                        || !Objects.equals(existing.getColumnDefault(), cm.getColumnDefault());
                if (changed) {
                    existing.setColumnComment(cm.getColumnComment());
                    existing.setDataType(cm.getDataType());
                    existing.setOrdinalPosition(cm.getOrdinalPosition());
                    existing.setNullable(cm.getNullable());
                    existing.setColumnDefault(cm.getColumnDefault());
                    existing.setLastCollectHistoryId(historyId);
                    metadataColumnMapper.updateById(existing);
                    updated++;
                }
            }
        }
        return new ColumnChange(added, updated);
    }

    private void finishHistory(CollectHistory history, int tableCount, int columnCount, int dbCount,
                               int addedTables, int updatedTables,
                               int addedColumns, int updatedColumns,
                               String errorMessage, String status) {
        LocalDateTime now = LocalDateTime.now();
        history.setStatus(status);
        history.setEndedAt(now);
        history.setDurationMs(java.time.Duration.between(history.getStartedAt(), now).toMillis());
        history.setTableCount(tableCount);
        history.setColumnCount(columnCount);
        history.setDbCount(dbCount);
        history.setAddedTableCount(addedTables);
        history.setUpdatedTableCount(updatedTables);
        history.setAddedColumnCount(addedColumns);
        history.setUpdatedColumnCount(updatedColumns);
        history.setErrorMessage(errorMessage);
        collectHistoryMapper.updateById(history);
    }

    private void updateTaskStatus(CollectTask task, CollectHistory history, String status) {
        task.setLastHistoryId(history.getId());
        task.setLastExecuteTime(LocalDateTime.now());
        if ("FAILED".equals(status)) {
            task.setStatus("ERROR");
        }
        collectTaskMapper.updateById(task);
    }

    private void failTask(CollectTask task, String triggerType, String message) {
        CollectHistory history = initHistory(task, triggerType);
        finishHistory(history, 0, 0, 0, 0, 0, 0, 0, message, "FAILED");
        updateTaskStatus(task, history, "FAILED");
        XxlJobHelper.handleFail(message);
    }

    private static class TableChange {
        final Long tableId;
        final boolean added;
        final boolean updated;

        TableChange(Long tableId, boolean added, boolean updated) {
            this.tableId = tableId;
            this.added = added;
            this.updated = updated;
        }
    }

    private static class ColumnChange {
        final int added;
        final int updated;

        ColumnChange(int added, int updated) {
            this.added = added;
            this.updated = updated;
        }
    }
}
