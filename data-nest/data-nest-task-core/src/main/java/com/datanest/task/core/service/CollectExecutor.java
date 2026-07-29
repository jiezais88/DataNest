package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.collect.ColumnMetadata;
import com.datanest.task.core.collect.ExtractorFactory;
import com.datanest.task.core.collect.MetadataExtractor;
import com.datanest.task.core.collect.TableMetadata;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 元数据采集任务执行核心，供 data-nest-worker 调用。
 * 本身不依赖 XXL-JOB 注解，保持 task-core 为纯库。
 */
@Service
public class CollectExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CollectExecutor.class);

    private final CollectTaskMapper collectTaskMapper;
    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper logMapper;
    private final CollectChangeDetailMapper changeDetailMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final ExtractorFactory extractorFactory;

    public CollectExecutor(CollectTaskMapper collectTaskMapper, CollectHistoryMapper collectHistoryMapper,
                           CollectExecutionLogMapper logMapper, CollectChangeDetailMapper changeDetailMapper,
                           DataSourceConnectionMapper dataSourceConnectionMapper, MetadataTableMapper metadataTableMapper,
                           MetadataColumnMapper metadataColumnMapper, ExtractorFactory extractorFactory) {
        this.collectTaskMapper = collectTaskMapper;
        this.collectHistoryMapper = collectHistoryMapper;
        this.logMapper = logMapper;
        this.changeDetailMapper = changeDetailMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.extractorFactory = extractorFactory;
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
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        // 设置运行中状态
        task.setStatus("RUNNING");
        collectTaskMapper.updateById(task);
        logger.info("runTask 任务状态已更新为 RUNNING: taskId={}", taskId);

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
        int deletedTables = 0;
        int addedColumns = 0;
        int updatedColumns = 0;
        int deletedColumns = 0;
        String errorMessage = null;
        String lastStatus = "SUCCESS";

        MetadataExtractor extractor = extractorFactory.getExtractor(ds.getType());
        try {
            log(history, "INFO", "开始采集任务：" + task.getName() + "，数据源：" + ds.getName());
            for (String schema : scope) {
                List<TableMetadata> tables = extractor.extractTables(ds, schema);
                dbCount++;
                log(history, "INFO", "采集到 " + tables.size() + " 张表，范围：" + (schema == null ? "默认" : schema));
                Set<String> collectedTableNames = tables.stream()
                        .map(TableMetadata::getTableName)
                        .collect(Collectors.toSet());
                for (TableMetadata table : tables) {
                    TableChange change = upsertTable(task, ds, table, history.getId());
                    if (change.added) addedTables++;
                    if (change.updated) updatedTables++;
                    tableCount++;
                    ColumnChange colChange = upsertColumns(change.tableId, table, history.getId(), change.added);
                    addedColumns += colChange.added;
                    updatedColumns += colChange.updated;
                    deletedColumns += colChange.deleted;
                    columnCount += table.getColumns().size();
                }
                int deletedInSchema = detectDeletedTables(ds, tables, collectedTableNames, history.getId());
                deletedTables += deletedInSchema;
            }
            log(history, "INFO", "采集完成：库/表/字段 = " + dbCount + "/" + tableCount + "/" + columnCount
                    + "，新增/修改/删除表 = " + addedTables + "/" + updatedTables + "/" + deletedTables
                    + "，新增/修改/删除字段 = " + addedColumns + "/" + updatedColumns + "/" + deletedColumns);
        } catch (Exception e) {
            logger.error("采集任务执行失败: taskId={}", taskId, e);
            errorMessage = e.getMessage();
            lastStatus = "FAILED";
            log(history, "ERROR", "采集失败：" + errorMessage);
        }

        finishHistory(history, tableCount, columnCount, dbCount, addedTables, updatedTables, deletedTables,
                addedColumns, updatedColumns, deletedColumns, errorMessage, lastStatus);
        updateTaskStatus(task, history, lastStatus);

        if ("FAILED".equals(lastStatus)) {
            throw new RuntimeException(errorMessage);
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
            mt.setSourceStatus("ONLINE");
            mt.setLastCollectHistoryId(historyId);
            metadataTableMapper.insert(mt);

            // 记录新增表变更明细
            writeChangeDetail(historyId, "ADDED_TABLE", table.getDatabaseName(),
                    table.getSchemaName(), table.getTableName(), null, null, null);
            return new TableChange(mt.getId(), true, false);
        } else {
            boolean wasOffline = !"ONLINE".equals(existing.getSourceStatus());
            boolean commentChanged = !Objects.equals(existing.getTableComment(), table.getTableComment());
            if (commentChanged) {
                String oldComment = existing.getTableComment();
                existing.setTableComment(table.getTableComment());

                // 记录表注释变更
                writeChangeDetail(historyId, "MODIFIED_TABLE", table.getDatabaseName(),
                        table.getSchemaName(), table.getTableName(), null, oldComment, table.getTableComment());
            }
            if (wasOffline) {
                existing.setSourceStatus("ONLINE");
            }
            // 无论表结构是否变化，都更新 last_collect_history_id，确保最近采集信息准确
            existing.setLastCollectHistoryId(historyId);
            metadataTableMapper.updateById(existing);
            return new TableChange(existing.getId(), false, commentChanged);
        }
    }

    private ColumnChange upsertColumns(Long tableId, TableMetadata table, Long historyId, boolean tableIsNew) {
        List<MetadataColumn> existingColumns = metadataColumnMapper.selectList(
                new QueryWrapper<MetadataColumn>().eq("table_id", tableId));
        Map<String, MetadataColumn> existingMap = existingColumns.stream()
                .collect(Collectors.toMap(MetadataColumn::getColumnName, c -> c, (a, b) -> a));

        int added = 0;
        int updated = 0;
        int deleted = 0;
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
                col.setSourceStatus("ONLINE");
                col.setLastCollectHistoryId(historyId);
                metadataColumnMapper.insert(col);
                added++;

                // 新增表的字段随表一起作为 ADDED_TABLE，便于前端展开；
                // 已存在表新增的字段属于字段变更，记为 ADDED_COLUMN。
                String newValue = formatColumnValue(cm.getDataType(), cm.getNullable(), cm.getColumnComment());
                if (tableIsNew) {
                    writeChangeDetail(historyId, "ADDED_TABLE", table.getDatabaseName(),
                            table.getSchemaName(), table.getTableName(), cm.getColumnName(),
                            null, newValue);
                } else {
                    writeChangeDetail(historyId, "ADDED_COLUMN", table.getDatabaseName(),
                            table.getSchemaName(), table.getTableName(), cm.getColumnName(),
                            null, newValue);
                }
            } else {
                boolean wasOffline = !"ONLINE".equals(existing.getSourceStatus());
                String oldDataType = existing.getDataType();
                String oldComment = existing.getColumnComment();
                Integer oldOrdinal = existing.getOrdinalPosition();
                Boolean oldNullable = existing.getNullable();
                String oldDefault = existing.getColumnDefault();

                boolean dataTypeChanged = !Objects.equals(oldDataType, cm.getDataType());
                boolean commentChanged = !Objects.equals(oldComment, cm.getColumnComment());
                boolean ordinalChanged = !Objects.equals(oldOrdinal, cm.getOrdinalPosition());
                boolean nullableChanged = !Objects.equals(oldNullable, cm.getNullable());
                boolean defaultChanged = !Objects.equals(oldDefault, cm.getColumnDefault());

                if (wasOffline) {
                    // 之前被标记为删除的字段重新出现，按新增字段处理
                    existing.setColumnComment(cm.getColumnComment());
                    existing.setDataType(cm.getDataType());
                    existing.setOrdinalPosition(cm.getOrdinalPosition());
                    existing.setNullable(cm.getNullable());
                    existing.setColumnDefault(cm.getColumnDefault());
                    existing.setSourceStatus("ONLINE");
                    existing.setLastCollectHistoryId(historyId);
                    metadataColumnMapper.updateById(existing);
                    added++;

                    String newValue = formatColumnValue(cm.getDataType(), cm.getNullable(), cm.getColumnComment());
                    writeChangeDetail(historyId, tableIsNew ? "ADDED_TABLE" : "ADDED_COLUMN",
                            table.getDatabaseName(), table.getSchemaName(), table.getTableName(),
                            cm.getColumnName(), null, newValue);
                } else if (dataTypeChanged || commentChanged || ordinalChanged || nullableChanged || defaultChanged) {
                    existing.setColumnComment(cm.getColumnComment());
                    existing.setDataType(cm.getDataType());
                    existing.setOrdinalPosition(cm.getOrdinalPosition());
                    existing.setNullable(cm.getNullable());
                    existing.setColumnDefault(cm.getColumnDefault());
                    existing.setLastCollectHistoryId(historyId);
                    metadataColumnMapper.updateById(existing);
                    updated++;

                    // 表变更与字段变更分开；字段内部按属性分项记录
                    writeColumnChangeDetail(historyId, table, existing, cm, "MODIFIED_COLUMN_TYPE", dataTypeChanged,
                            oldDataType, cm.getDataType());
                    writeColumnChangeDetail(historyId, table, existing, cm, "MODIFIED_COLUMN_COMMENT", commentChanged,
                            oldComment, cm.getColumnComment());
                    writeColumnChangeDetail(historyId, table, existing, cm, "MODIFIED_COLUMN_ORDINAL", ordinalChanged,
                            String.valueOf(oldOrdinal), String.valueOf(cm.getOrdinalPosition()));
                    writeColumnChangeDetail(historyId, table, existing, cm, "MODIFIED_COLUMN_NULLABLE", nullableChanged,
                            formatNullable(oldNullable), formatNullable(cm.getNullable()));
                    writeColumnChangeDetail(historyId, table, existing, cm, "MODIFIED_COLUMN_DEFAULT", defaultChanged,
                            formatDefault(oldDefault), formatDefault(cm.getColumnDefault()));
                }
                // 从已有字段集合中移除，剩余即为已删除字段
                existingMap.remove(cm.getColumnName());
            }
        }

        // 剩余字段为源库中已不存在的字段
        for (MetadataColumn remaining : existingMap.values()) {
            if (!"ONLINE".equals(remaining.getSourceStatus())) {
                continue;
            }
            String oldValue = formatColumnValue(remaining.getDataType(), remaining.getNullable(), remaining.getColumnComment());
            remaining.setSourceStatus("OFFLINE");
            remaining.setLastCollectHistoryId(historyId);
            metadataColumnMapper.updateById(remaining);
            deleted++;

            writeChangeDetail(historyId, "DELETED_COLUMN", table.getDatabaseName(), table.getSchemaName(),
                    table.getTableName(), remaining.getColumnName(), oldValue, null);
        }
        return new ColumnChange(added, updated, deleted);
    }

    private String formatColumnValue(String dataType, Boolean nullable, String comment) {
        return dataType + "|"
                + (Boolean.TRUE.equals(nullable) ? "true" : "false") + "|"
                + (comment == null ? "" : comment);
    }

    private void writeColumnChangeDetail(Long historyId, TableMetadata table, MetadataColumn existing,
                                         ColumnMetadata cm, String changeType, boolean changed,
                                         String oldValue, String newValue) {
        if (!changed) {
            return;
        }
        writeChangeDetail(historyId, changeType, table.getDatabaseName(), table.getSchemaName(),
                table.getTableName(), cm.getColumnName(), oldValue, newValue);
    }

    private String formatNullable(Boolean nullable) {
        return Boolean.TRUE.equals(nullable) ? "可为空" : "不可为空";
    }

    private String formatDefault(String defaultValue) {
        return defaultValue == null ? "NULL" : defaultValue;
    }

    private void finishHistory(CollectHistory history, int tableCount, int columnCount, int dbCount,
                               int addedTables, int updatedTables, int deletedTables,
                               int addedColumns, int updatedColumns, int deletedColumns,
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
        history.setDeletedTableCount(deletedTables);
        history.setAddedColumnCount(addedColumns);
        history.setUpdatedColumnCount(updatedColumns);
        history.setDeletedColumnCount(deletedColumns);
        history.setErrorMessage(errorMessage);
        collectHistoryMapper.updateById(history);
    }

    private int detectDeletedTables(DataSourceConnection ds, List<TableMetadata> collectedTables,
                                    Set<String> collectedTableNames, Long historyId) {
        if (collectedTables.isEmpty()) {
            return 0;
        }
        TableMetadata first = collectedTables.get(0);
        String databaseName = first.getDatabaseName();
        String schemaName = first.getSchemaName();

        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", ds.getId())
                .eq("database_name", databaseName)
                .eq("COALESCE(schema_name, '')", schemaName == null ? "" : schemaName)
                .eq("source_status", "ONLINE");
        List<MetadataTable> existingTables = metadataTableMapper.selectList(wrapper);

        int deleted = 0;
        for (MetadataTable existing : existingTables) {
            if (!collectedTableNames.contains(existing.getTableName())) {
                existing.setSourceStatus("OFFLINE");
                existing.setLastCollectHistoryId(historyId);
                metadataTableMapper.updateById(existing);

                // 同步把该表下的字段也标记为已删除
                MetadataColumn columnUpdate = new MetadataColumn();
                columnUpdate.setSourceStatus("OFFLINE");
                metadataColumnMapper.update(columnUpdate,
                        new QueryWrapper<MetadataColumn>().eq("table_id", existing.getId()));

                writeChangeDetail(historyId, "DELETED_TABLE", existing.getDatabaseName(),
                        existing.getSchemaName(), existing.getTableName(), null,
                        existing.getTableComment(), null);
                deleted++;
            }
        }
        return deleted;
    }

    private void updateTaskStatus(CollectTask task, CollectHistory history, String status) {
        task.setLastHistoryId(history.getId());
        task.setLastExecuteTime(LocalDateTime.now());
        task.setStatus(status);
        collectTaskMapper.updateById(task);
    }

    private void failTask(CollectTask task, String triggerType, String message) {
        CollectHistory history = initHistory(task, triggerType);
        finishHistory(history, 0, 0, 0, 0, 0, 0, 0, 0, 0, message, "FAILED");
        updateTaskStatus(task, history, "FAILED");
        throw new RuntimeException(message);
    }

    private void writeChangeDetail(Long historyId, String changeType, String dbName,
                                   String schemaName, String tableName, String columnName,
                                   String oldValue, String newValue) {
        CollectChangeDetail detail = new CollectChangeDetail();
        detail.setHistoryId(historyId);
        detail.setChangeType(changeType);
        detail.setDatabaseName(dbName);
        detail.setSchemaName(schemaName);
        detail.setTableName(tableName);
        detail.setColumnName(columnName);
        detail.setOldValue(oldValue);
        detail.setNewValue(newValue);
        detail.setCreatedAt(LocalDateTime.now());
        changeDetailMapper.insert(detail);
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
        final int deleted;

        ColumnChange(int added, int updated, int deleted) {
            this.added = added;
            this.updated = updated;
            this.deleted = deleted;
        }
    }
}
