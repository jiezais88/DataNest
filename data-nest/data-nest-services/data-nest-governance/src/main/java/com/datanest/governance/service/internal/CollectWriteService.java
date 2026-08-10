package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.governance.api.dto.CollectChangeDetailBatchRequest;
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
import com.datanest.governance.api.dto.UpsertColumnsResultDTO;
import com.datanest.governance.api.dto.UpsertTableResultDTO;
import com.datanest.governance.entity.CollectChangeDetail;
import com.datanest.governance.entity.CollectExecutionLog;
import com.datanest.governance.entity.CollectHistory;
import com.datanest.governance.entity.CollectTask;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.CollectChangeDetailMapper;
import com.datanest.governance.mapper.CollectExecutionLogMapper;
import com.datanest.governance.mapper.CollectHistoryMapper;
import com.datanest.governance.mapper.CollectTaskMapper;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采集回写域内部逻辑（实现 governance-api 的 CollectWriteApi 契约）。
 * <p>
 * 微服务化 4.1：worker 采集执行过程中的治理表回写收进本服务，
 * 语义逐行对齐原 task-core 的 CollectExecutor（upsertTable/upsertColumns/detectDeletedTables/
 * initHistory/finishHistory/log/writeChangeDetail/updateTaskStatus）。
 */
@Service
public class CollectWriteService {

    private static final Logger logger = LoggerFactory.getLogger(CollectWriteService.class);

    private final CollectTaskMapper collectTaskMapper;
    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper logMapper;
    private final CollectChangeDetailMapper changeDetailMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;

    public CollectWriteService(CollectTaskMapper collectTaskMapper,
                               CollectHistoryMapper collectHistoryMapper,
                               CollectExecutionLogMapper logMapper,
                               CollectChangeDetailMapper changeDetailMapper,
                               MetadataTableMapper metadataTableMapper,
                               MetadataColumnMapper metadataColumnMapper) {
        this.collectTaskMapper = collectTaskMapper;
        this.collectHistoryMapper = collectHistoryMapper;
        this.logMapper = logMapper;
        this.changeDetailMapper = changeDetailMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
    }

    /**
     * 查询采集任务定义（对齐 CollectExecutor.runTask 的 collectTaskMapper.selectById；
     * 不存在返回 null，由调用方按「任务不存在」fail-fast）。
     */
    public CollectTaskInfoDTO getTask(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        CollectTaskInfoDTO dto = new CollectTaskInfoDTO();
        dto.setId(task.getId());
        dto.setName(task.getName());
        dto.setDatasourceId(task.getDatasourceId());
        dto.setDatasourceName(task.getDatasourceName());
        dto.setScope(task.getScope());
        dto.setCollectMode(task.getCollectMode());
        dto.setTriggerType(task.getTriggerType());
        dto.setCronExpression(task.getCronExpression());
        dto.setStatus(task.getStatus());
        dto.setLastExecuteTime(formatTime(task.getLastExecuteTime()));
        dto.setLastHistoryId(task.getLastHistoryId());
        dto.setDescription(task.getDescription());
        dto.setSchedulerJobId(task.getSchedulerJobId());
        dto.setScheduleEnabled(task.getScheduleEnabled());
        dto.setNextExecutionTime(formatTime(task.getNextExecutionTime()));
        dto.setCreatedBy(task.getCreatedBy());
        dto.setUpdatedBy(task.getUpdatedBy());
        dto.setCreatedAt(formatTime(task.getCreatedAt()));
        dto.setUpdatedAt(formatTime(task.getUpdatedAt()));
        return dto;
    }

    /**
     * 回写任务状态（对齐 CollectExecutor 的「置 RUNNING」与 updateTaskStatus）：
     * status 必回写；lastHistoryId / lastExecuteTime 仅在为非空时回写。
     */
    public void markTaskStatus(Long id, CollectTaskMarkStatusRequest request) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        task.setStatus(request.getStatus());
        if (request.getLastHistoryId() != null) {
            task.setLastHistoryId(request.getLastHistoryId());
        }
        if (request.getLastExecuteTime() != null && !request.getLastExecuteTime().isBlank()) {
            task.setLastExecuteTime(parseTime(request.getLastExecuteTime()));
        }
        collectTaskMapper.updateById(task);
    }

    /**
     * 初始化采集历史（对齐 CollectExecutor.initHistory：RUNNING + startedAt=now + 统计列清零），
     * 返回 historyId。
     */
    public Long createHistory(CollectHistoryCreateRequest request) {
        CollectHistory history = new CollectHistory();
        history.setTaskId(request.getTaskId());
        history.setTaskName(request.getTaskName());
        history.setDatasourceId(request.getDatasourceId());
        history.setTriggerType(request.getTriggerType());
        history.setStatus(ExecutionStatus.RUNNING.getCode());
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
        collectHistoryMapper.insert(history);
        return history.getId();
    }

    /**
     * 轻量查询历史状态（对齐 CollectExecutor.isTerminated：只读 id/status 供停止轮询；
     * 不存在返回 null）。
     */
    public CollectHistoryInfoDTO getHistory(Long id) {
        CollectHistory history = collectHistoryMapper.selectById(id);
        if (history == null) {
            return null;
        }
        CollectHistoryInfoDTO dto = new CollectHistoryInfoDTO();
        dto.setId(history.getId());
        dto.setStatus(history.getStatus());
        return dto;
    }

    /**
     * 收尾采集历史（对齐 CollectExecutor.finishHistory）：
     * 终态 + endedAt + durationMs + 全部统计列 + errorMessage 一次性回写。
     * durationMs 为空时按 startedAt~endedAt 计算（对齐源的 Duration.between 逻辑）。
     */
    public void finishHistory(Long id, CollectHistoryFinishRequest request) {
        CollectHistory history = collectHistoryMapper.selectById(id);
        if (history == null) {
            throw new IllegalArgumentException("采集历史不存在: " + id);
        }
        LocalDateTime endedAt = request.getEndedAt() == null || request.getEndedAt().isBlank()
                ? LocalDateTime.now()
                : parseTime(request.getEndedAt());
        history.setStatus(request.getStatus());
        history.setEndedAt(endedAt);
        history.setDurationMs(request.getDurationMs() != null
                ? request.getDurationMs()
                : Duration.between(history.getStartedAt(), endedAt).toMillis());
        history.setTableCount(request.getTableCount());
        history.setColumnCount(request.getColumnCount());
        history.setDbCount(request.getDbCount());
        history.setAddedTableCount(request.getAddedTableCount());
        history.setUpdatedTableCount(request.getUpdatedTableCount());
        history.setDeletedTableCount(request.getDeletedTableCount());
        history.setAddedColumnCount(request.getAddedColumnCount());
        history.setUpdatedColumnCount(request.getUpdatedColumnCount());
        history.setDeletedColumnCount(request.getDeletedColumnCount());
        history.setErrorMessage(request.getErrorMessage());
        collectHistoryMapper.updateById(history);
    }

    /**
     * 追加执行日志（对齐 CollectExecutor.log：historyId/taskId/level/message 四列，
     * taskId 从历史记录带出；createdAt 与源一致不显式赋值，走表默认值）。
     */
    public void appendLogs(Long id, CollectLogAppendRequest request) {
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return;
        }
        CollectHistory history = collectHistoryMapper.selectById(id);
        if (history == null) {
            throw new IllegalArgumentException("采集历史不存在: " + id);
        }
        for (CollectLogAppendRequest.Entry entry : request.getEntries()) {
            CollectExecutionLog log = new CollectExecutionLog();
            log.setHistoryId(history.getId());
            log.setTaskId(history.getTaskId());
            log.setLevel(entry.getLevel());
            log.setMessage(entry.getMessage());
            logMapper.insert(log);
        }
    }

    /**
     * 批量写入采集变更明细（对齐 CollectExecutor.writeChangeDetail：createdAt 取当前时间）。
     */
    public void batchChangeDetails(Long id, CollectChangeDetailBatchRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return;
        }
        for (CollectChangeDetailBatchRequest.Item item : request.getItems()) {
            writeChangeDetail(id, item.getChangeType(), item.getDatabaseName(), item.getSchemaName(),
                    item.getTableName(), item.getColumnName(), item.getOldValue(), item.getNewValue());
        }
    }

    /**
     * 元数据表 upsert（逐行对齐 CollectExecutor.upsertTable）：
     * 不存在则插入（ONLINE + 记 ADDED_TABLE）；已存在则按注释变化记 MODIFIED_TABLE、
     * OFFLINE 复活为 ONLINE，并始终回写 last_collect_history_id。
     * 微服务化 4.2：返回 tableId + isNew/changed 计数（变更明细落库的副产品），
     * 供 worker 累加采集历史统计列。
     */
    @Transactional
    public UpsertTableResultDTO upsertTable(CollectUpsertTableRequest request) {
        Long historyId = request.getCollectHistoryId();
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", request.getDatasourceId())
                .eq("database_name", request.getDatabaseName())
                .eq("COALESCE(schema_name, '')", request.getSchemaName() == null ? "" : request.getSchemaName())
                .eq("table_name", request.getTableName());
        MetadataTable existing = metadataTableMapper.selectOne(wrapper);

        UpsertTableResultDTO result = new UpsertTableResultDTO();
        if (existing == null) {
            MetadataTable mt = new MetadataTable();
            mt.setDatasourceId(request.getDatasourceId());
            mt.setDatabaseName(request.getDatabaseName());
            mt.setSchemaName(request.getSchemaName());
            mt.setTableName(request.getTableName());
            mt.setTableComment(request.getTableComment());
            mt.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
            mt.setLastCollectHistoryId(historyId);
            metadataTableMapper.insert(mt);

            // 记录新增表变更明细
            writeChangeDetail(historyId, "ADDED_TABLE", request.getDatabaseName(),
                    request.getSchemaName(), request.getTableName(), null, null, null);
            result.setTableId(mt.getId());
            result.setIsNew(true);
            result.setChanged(false);
            return result;
        } else {
            boolean wasOffline = !MetadataSourceStatus.ONLINE.getCode().equals(existing.getSourceStatus());
            boolean commentChanged = !Objects.equals(existing.getTableComment(), request.getTableComment());
            if (commentChanged) {
                String oldComment = existing.getTableComment();
                existing.setTableComment(request.getTableComment());

                // 记录表注释变更
                writeChangeDetail(historyId, "MODIFIED_TABLE", request.getDatabaseName(),
                        request.getSchemaName(), request.getTableName(), null, oldComment, request.getTableComment());
            }
            if (wasOffline) {
                existing.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
            }
            // Sprint 8 F1 评审修复：表注释变更/OFFLINE 复活属真实元数据变更，刷新 updated_at
            // （否则外部采集表的 updated_at 停留在首次采集时间，资产目录 sort=latest 失真）
            if (commentChanged || wasOffline) {
                existing.setUpdatedAt(LocalDateTime.now());
            }
            // 无论表结构是否变化，都更新 last_collect_history_id，确保最近采集信息准确
            existing.setLastCollectHistoryId(historyId);
            metadataTableMapper.updateById(existing);
            result.setTableId(existing.getId());
            result.setIsNew(false);
            result.setChanged(commentChanged);
            return result;
        }
    }

    /**
     * 元数据字段 diff upsert（逐行对齐 CollectExecutor.upsertColumns）：
     * 逐字段 upsert（新字段插入、复活字段按新增处理、属性变化按 MODIFIED_COLUMN_* 分项记变更）、
     * 本次清单中消失的 ONLINE 字段置 OFFLINE（记 DELETED_COLUMN）。
     * 微服务化 4.2：返回 added/updated/deleted/resurrected 计数（变更明细落库的副产品），
     * 供 worker 累加采集历史统计列（原实现把复活计入 added，此处拆出由调用方合并）。
     */
    @Transactional
    public UpsertColumnsResultDTO upsertColumns(CollectUpsertColumnsRequest request) {
        Long tableId = request.getTableId();
        Long historyId = request.getCollectHistoryId();
        boolean tableIsNew = Boolean.TRUE.equals(request.getTableIsNew());
        List<CollectUpsertColumnsRequest.ColumnItem> columns = request.getColumns() == null
                ? List.of() : request.getColumns();

        List<MetadataColumn> existingColumns = metadataColumnMapper.selectList(
                new QueryWrapper<MetadataColumn>().eq("table_id", tableId));
        Map<String, MetadataColumn> existingMap = existingColumns.stream()
                .collect(Collectors.toMap(MetadataColumn::getColumnName, c -> c, (a, b) -> a));

        int added = 0;
        int updated = 0;
        int deleted = 0;
        int resurrected = 0;
        for (CollectUpsertColumnsRequest.ColumnItem cm : columns) {
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
                col.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
                col.setLastCollectHistoryId(historyId);
                metadataColumnMapper.insert(col);
                added++;

                // 新增表的字段随表一起作为 ADDED_TABLE，便于前端展开；
                // 已存在表新增的字段属于字段变更，记为 ADDED_COLUMN。
                String newValue = formatColumnValue(cm.getDataType(), cm.getNullable(), cm.getColumnComment());
                if (tableIsNew) {
                    writeChangeDetail(historyId, "ADDED_TABLE", request.getDatabaseName(),
                            request.getSchemaName(), request.getTableName(), cm.getColumnName(),
                            null, newValue);
                } else {
                    writeChangeDetail(historyId, "ADDED_COLUMN", request.getDatabaseName(),
                            request.getSchemaName(), request.getTableName(), cm.getColumnName(),
                            null, newValue);
                }
            } else {
                boolean wasOffline = !MetadataSourceStatus.ONLINE.getCode().equals(existing.getSourceStatus());
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
                    existing.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
                    existing.setLastCollectHistoryId(historyId);
                    metadataColumnMapper.updateById(existing);
                    resurrected++;

                    String newValue = formatColumnValue(cm.getDataType(), cm.getNullable(), cm.getColumnComment());
                    writeChangeDetail(historyId, tableIsNew ? "ADDED_TABLE" : "ADDED_COLUMN",
                            request.getDatabaseName(), request.getSchemaName(), request.getTableName(),
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
                    writeColumnChangeDetail(historyId, request, cm, "MODIFIED_COLUMN_TYPE", dataTypeChanged,
                            oldDataType, cm.getDataType());
                    writeColumnChangeDetail(historyId, request, cm, "MODIFIED_COLUMN_COMMENT", commentChanged,
                            oldComment, cm.getColumnComment());
                    writeColumnChangeDetail(historyId, request, cm, "MODIFIED_COLUMN_ORDINAL", ordinalChanged,
                            String.valueOf(oldOrdinal), String.valueOf(cm.getOrdinalPosition()));
                    writeColumnChangeDetail(historyId, request, cm, "MODIFIED_COLUMN_NULLABLE", nullableChanged,
                            formatNullable(oldNullable), formatNullable(cm.getNullable()));
                    writeColumnChangeDetail(historyId, request, cm, "MODIFIED_COLUMN_DEFAULT", defaultChanged,
                            formatDefault(oldDefault), formatDefault(cm.getColumnDefault()));
                }
                // 从已有字段集合中移除，剩余即为已删除字段
                existingMap.remove(cm.getColumnName());
            }
        }

        // 剩余字段为源库中已不存在的字段
        for (MetadataColumn remaining : existingMap.values()) {
            if (!MetadataSourceStatus.ONLINE.getCode().equals(remaining.getSourceStatus())) {
                continue;
            }
            String oldValue = formatColumnValue(remaining.getDataType(), remaining.getNullable(), remaining.getColumnComment());
            remaining.setSourceStatus(MetadataSourceStatus.OFFLINE.getCode());
            remaining.setLastCollectHistoryId(historyId);
            metadataColumnMapper.updateById(remaining);
            deleted++;

            writeChangeDetail(historyId, "DELETED_COLUMN", request.getDatabaseName(), request.getSchemaName(),
                    request.getTableName(), remaining.getColumnName(), oldValue, null);
        }

        UpsertColumnsResultDTO result = new UpsertColumnsResultDTO();
        result.setAddedCount(added);
        result.setUpdatedCount(updated);
        result.setDeletedCount(deleted);
        result.setResurrectedCount(resurrected);

        // Sprint 8 F1 评审修复：存量表的字段级真实变更（新增/修改/删除/复活）同步刷新表 updated_at
        // （sort=latest 口径与「元数据有变化」对齐；新表 updated_at 由 insert 默认值兜底，不重复刷）
        if (!tableIsNew && (added + updated + deleted + resurrected) > 0) {
            UpdateWrapper<MetadataTable> touch = new UpdateWrapper<>();
            touch.eq("id", tableId).set("updated_at", LocalDateTime.now());
            metadataTableMapper.update(null, touch);
        }
        return result;
    }

    /**
     * 删除表检测（逐行对齐 CollectExecutor.detectDeletedTables）：
     * 同数据源+库+schema 下 ONLINE 但不在本次清单的表置 OFFLINE，并同步把该表下的字段置 OFFLINE，
     * 记 DELETED_TABLE 变更明细；清单为空时直接跳过（对齐源 collectedTables.isEmpty() 不检测，
     * 避免清单未采完误判删除）。
     * 微服务化 4.2：返回删除表/字段计数（变更明细落库的副产品），供 worker 累加采集历史统计列。
     */
    @Transactional
    public DetectDeletedResultDTO detectDeletedTables(CollectDetectDeletedTablesRequest request) {
        DetectDeletedResultDTO result = new DetectDeletedResultDTO();
        result.setDeletedTableCount(0);
        result.setDeletedColumnCount(0);
        if (request.getCurrentTableNames() == null || request.getCurrentTableNames().isEmpty()) {
            return result;
        }
        Long historyId = request.getCollectHistoryId();
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", request.getDatasourceId())
                .eq("database_name", request.getDatabaseName())
                .eq("COALESCE(schema_name, '')", request.getSchemaName() == null ? "" : request.getSchemaName())
                .eq("source_status", MetadataSourceStatus.ONLINE.getCode());
        List<MetadataTable> existingTables = metadataTableMapper.selectList(wrapper);

        int deletedTables = 0;
        int deletedColumns = 0;
        for (MetadataTable existing : existingTables) {
            if (!request.getCurrentTableNames().contains(existing.getTableName())) {
                existing.setSourceStatus(MetadataSourceStatus.OFFLINE.getCode());
                existing.setLastCollectHistoryId(historyId);
                metadataTableMapper.updateById(existing);

                // 同步把该表下的字段也标记为已删除
                MetadataColumn columnUpdate = new MetadataColumn();
                columnUpdate.setSourceStatus(MetadataSourceStatus.OFFLINE.getCode());
                deletedColumns += metadataColumnMapper.update(columnUpdate,
                        new QueryWrapper<MetadataColumn>().eq("table_id", existing.getId()));

                writeChangeDetail(historyId, "DELETED_TABLE", existing.getDatabaseName(),
                        existing.getSchemaName(), existing.getTableName(), null,
                        existing.getTableComment(), null);
                deletedTables++;
            }
        }
        result.setDeletedTableCount(deletedTables);
        result.setDeletedColumnCount(deletedColumns);
        return result;
    }

    private String formatColumnValue(String dataType, Boolean nullable, String comment) {
        return dataType + "|"
                + (Boolean.TRUE.equals(nullable) ? "true" : "false") + "|"
                + (comment == null ? "" : comment);
    }

    private void writeColumnChangeDetail(Long historyId, CollectUpsertColumnsRequest table,
                                         CollectUpsertColumnsRequest.ColumnItem cm, String changeType, boolean changed,
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

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private LocalDateTime parseTime(String time) {
        return LocalDateTime.parse(time, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
