package com.datanest.engineering.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.api.dto.FieldMappingItemDTO;
import com.datanest.engineering.api.dto.FinishExecutionRequest;
import com.datanest.engineering.api.dto.SyncHistoryCreateRequest;
import com.datanest.engineering.api.dto.SyncHistoryFinishRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.api.dto.SyncLogAppendRequest;
import com.datanest.engineering.api.dto.SyncStatusMarkRequest;
import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.entity.SyncJobHistory;
import com.datanest.engineering.entity.SyncJobLog;
import com.datanest.engineering.mapper.SyncJobHistoryMapper;
import com.datanest.engineering.mapper.SyncJobLogMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务域内部接口服务（engineering 归属表 sync_job / sync_job_history / sync_job_log）。
 * <p>
 * 状态翻转、日志续号、收割与清理语义逐一对照 task-core 原实现
 * （SyncJobExecutorService / SyncJobRetryService / 原卡死收割服务 /
 * SyncHistoryCleanupHandler），逻辑整体下沉 engineering 保持原子性。
 */
@Service
public class InternalSyncJobService {

    private static final Logger logger = LoggerFactory.getLogger(InternalSyncJobService.class);

    /** 日志批量写入批次大小（与 SyncJobExecutorService.LOG_BATCH_SIZE 一致） */
    private static final int LOG_BATCH_SIZE = 500;

    private static final int DEFAULT_STUCK_MINUTES = 120;

    private static final int DEFAULT_RETAIN_DAYS = 30;

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;

    public InternalSyncJobService(SyncJobMapper syncJobMapper,
                                  SyncJobHistoryMapper syncJobHistoryMapper,
                                  SyncJobLogMapper syncJobLogMapper) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
    }

    // ==================== 任务定义与状态 ====================

    public SyncJobInfo getJobById(Long id) {
        return toJobInfo(syncJobMapper.selectById(id));
    }

    public List<SyncJobInfo> listByDatasource(Long datasourceId) {
        return syncJobMapper.selectBySourceDatasourceId(datasourceId)
                .stream().map(InternalSyncJobService::toJobInfo).toList();
    }

    public boolean markRunning(Long id) {
        return syncJobMapper.update(null, new UpdateWrapper<SyncJob>()
                .set("execution_status", ExecutionStatus.RUNNING.getCode())
                .set("updated_at", LocalDateTime.now())
                .eq("id", id)) > 0;
    }

    /**
     * execution_status 条件更新：expectedLastHistoryId 非空时保留 last_history_id 保护语义
     * （与原 task-core 收割器翻转 sync_job 的条件一致：
     * last_history_id 为空或等于该值才翻转，避免覆盖新一轮执行的状态）。
     */
    public boolean markStatus(Long id, SyncStatusMarkRequest request) {
        UpdateWrapper<SyncJob> wrapper = new UpdateWrapper<SyncJob>()
                .set("execution_status", request.getStatus())
                .set("updated_at", LocalDateTime.now())
                .eq("id", id);
        if (request.getExpectedLastHistoryId() != null) {
            wrapper.and(w -> w.isNull("last_history_id")
                    .or().eq("last_history_id", request.getExpectedLastHistoryId()));
        }
        return syncJobMapper.update(null, wrapper) > 0;
    }

    public void finishExecution(Long id, FinishExecutionRequest request) {
        syncJobMapper.update(null, new UpdateWrapper<SyncJob>()
                .set("last_execute_time",
                        request.getLastExecuteTime() != null ? request.getLastExecuteTime() : LocalDateTime.now())
                .set("last_history_id", request.getHistoryId())
                .set("updated_at", LocalDateTime.now())
                .eq("id", id));
    }

    public void updateSchedulerJobId(Long id, Long schedulerJobId) {
        syncJobMapper.update(null, new UpdateWrapper<SyncJob>()
                .set("scheduler_job_id", schedulerJobId)
                .set("updated_at", LocalDateTime.now())
                .eq("id", id));
    }

    // ==================== 执行历史与日志 ====================

    /**
     * 新建 RUNNING 历史（覆盖 init 与 retry 两种插入，字段默认值与
     * SyncJobExecutorService.initHistory / SyncJobRetryService.claimAndCreateRetryHistory 一致）。
     */
    public Long createHistory(SyncHistoryCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(request.getSyncJobId());
        history.setTriggerType(request.getTriggerType());
        history.setDagExecutionId(request.getDagExecutionId());
        history.setParentHistoryId(request.getParentHistoryId());
        history.setRetryCount(request.getRetryCount() == null ? 0 : request.getRetryCount());
        history.setStatus(ExecutionStatus.RUNNING.getCode());
        history.setStartTime(now);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(now);
        syncJobHistoryMapper.insert(history);
        return history.getId();
    }

    /** 单条历史轻量查询（停止 watcher 每 2s 轮询 + 执行收尾重读用，只查必要列） */
    public SyncHistoryInfo getHistoryLight(Long id) {
        SyncJobHistory history = syncJobHistoryMapper.selectOne(new QueryWrapper<SyncJobHistory>()
                .select("id", "sync_job_id", "status", "error_message", "source_rows", "target_rows",
                        "start_time", "end_time", "trigger_type", "retry_count")
                .eq("id", id));
        return toHistoryInfo(history);
    }

    public void finishHistory(Long id, SyncHistoryFinishRequest request) {
        SyncJobHistory history = syncJobHistoryMapper.selectById(id);
        if (history == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND);
        }
        history.setStatus(request.getStatus());
        if (request.getErrorMessage() != null) {
            history.setErrorMessage(request.getErrorMessage());
        }
        if (request.getSourceRows() != null) {
            history.setSourceRows(request.getSourceRows());
        }
        if (request.getTargetRows() != null) {
            history.setTargetRows(request.getTargetRows());
        }
        if (request.getTableResults() != null) {
            history.setTableResults(request.getTableResults());
        }
        LocalDateTime endTime = request.getEndTime() != null ? request.getEndTime() : LocalDateTime.now();
        history.setEndTime(endTime);
        if (request.getDurationMs() != null) {
            history.setDurationMs(request.getDurationMs());
        } else if (history.getStartTime() != null) {
            history.setDurationMs(Duration.between(history.getStartTime(), endTime).toMillis());
        }
        syncJobHistoryMapper.updateById(history);
    }

    /**
     * 最新一条历史；notBefore 非空时只接受 end_time 不早于该值的记录
     * （负数耗时修复：RUNNING 历史 end_time 为 NULL 自然被过滤）。
     */
    public SyncHistoryInfo latestHistory(Long syncJobId, LocalDateTime notBefore) {
        QueryWrapper<SyncJobHistory> wrapper = new QueryWrapper<SyncJobHistory>()
                .eq("sync_job_id", syncJobId);
        if (notBefore != null) {
            wrapper.isNotNull("end_time").ge("end_time", notBefore);
        }
        wrapper.orderByDesc("id").last("LIMIT 1");
        return toHistoryInfo(syncJobHistoryMapper.selectOne(wrapper));
    }

    public long successCount(Long syncJobId) {
        Long count = syncJobHistoryMapper.selectCount(new QueryWrapper<SyncJobHistory>()
                .eq("sync_job_id", syncJobId)
                .eq("status", ExecutionStatus.SUCCESS.getCode()));
        return count == null ? 0L : count;
    }

    /**
     * 追加同步日志：服务端续号（line_num = 现有行数 + 序号），一次事务批量插入，500/批分片。
     */
    @Transactional
    public void appendLogs(Long historyId, SyncLogAppendRequest request) {
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return;
        }
        SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND);
        }
        Long count = syncJobLogMapper.selectCount(new QueryWrapper<SyncJobLog>().eq("history_id", historyId));
        int lineNum = (int) (count == null ? 0 : count) + 1;
        LocalDateTime now = LocalDateTime.now();
        List<SyncJobLog> batch = new ArrayList<>(Math.min(request.getEntries().size(), LOG_BATCH_SIZE));
        for (SyncLogAppendRequest.Entry entry : request.getEntries()) {
            SyncJobLog log = new SyncJobLog();
            log.setId(IdWorker.getId());
            log.setHistoryId(historyId);
            log.setSyncJobId(history.getSyncJobId());
            log.setLevel(entry.getLevel() != null ? entry.getLevel() : detectLevel(entry.getContent()));
            log.setMessage(entry.getContent());
            log.setLineNum(lineNum++);
            log.setTableName(entry.getTableName());
            log.setCreatedAt(now);
            batch.add(log);
            if (batch.size() >= LOG_BATCH_SIZE) {
                syncJobLogMapper.insertBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            syncJobLogMapper.insertBatch(batch);
        }
    }

    // ==================== 重试 ====================

    /** 到期待重试的失败历史（与 SyncJobRetryService.listDueRetries 一致） */
    public List<SyncHistoryInfo> dueRetries(int limit) {
        return syncJobHistoryMapper.selectList(new QueryWrapper<SyncJobHistory>()
                        .eq("status", ExecutionStatus.FAILED.getCode())
                        .isNotNull("next_retry_at")
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(InternalSyncJobService::toHistoryInfo).toList();
    }

    public void registerRetry(Long id, LocalDateTime nextRetryAt) {
        syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", nextRetryAt)
                .eq("id", id));
    }

    /** 原子认领：条件清空 next_retry_at，并发扫描只有一个实例认领成功 */
    public boolean claimRetry(Long id) {
        return syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", null)
                .eq("id", id)
                .isNotNull("next_retry_at")) > 0;
    }

    /** 重试历史收尾（标 FAILED，不清空 retry_count，与 markRetryHistoryFailed 一致） */
    public void markHistoryFailed(Long id, String errorMessage) {
        SyncJobHistory history = syncJobHistoryMapper.selectById(id);
        if (history == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        history.setStatus(ExecutionStatus.FAILED.getCode());
        history.setErrorMessage(errorMessage);
        history.setEndTime(now);
        if (history.getStartTime() != null) {
            history.setDurationMs(Duration.between(history.getStartTime(), now).toMillis());
        }
        syncJobHistoryMapper.updateById(history);
    }

    // ==================== 批量操作（收割 / 清理） ====================

    /**
     * 收割卡死 RUNNING 的 sync_job_history（start_time 早于阈值），置 FAILED 并把对应
     * sync_job 条件翻转 FAILED（与原 task-core 收割实现一致）。
     * 不使用整体事务：逐条 update 即时提交，避免收割到一半失败导致全部回滚。
     */
    public int reapStuckSync(Integer stuckBeforeMinutes) {
        int minutes = stuckBeforeMinutes == null || stuckBeforeMinutes < 1 ? DEFAULT_STUCK_MINUTES : stuckBeforeMinutes;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
        List<SyncJobHistory> stuck = syncJobHistoryMapper.selectList(new QueryWrapper<SyncJobHistory>()
                .eq("status", ExecutionStatus.RUNNING.getCode())
                .isNotNull("start_time")
                .lt("start_time", threshold));
        if (stuck.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = "同步执行卡死收割：RUNNING 超过 " + minutes + " 分钟未结束，判定执行方失联，标记为 FAILED";
        for (SyncJobHistory history : stuck) {
            history.setStatus(ExecutionStatus.FAILED.getCode());
            history.setErrorMessage(reason);
            history.setEndTime(now);
            if (history.getDurationMs() == null && history.getStartTime() != null) {
                history.setDurationMs(Duration.between(history.getStartTime(), now).toMillis());
            }
            syncJobHistoryMapper.updateById(history);

            // 主表 execution_status 仍为 RUNNING 且指向该历史（或未指向任何历史）时才翻转
            syncJobMapper.update(null, new UpdateWrapper<SyncJob>()
                    .set("execution_status", ExecutionStatus.FAILED.getCode())
                    .set("updated_at", now)
                    .eq("id", history.getSyncJobId())
                    .eq("execution_status", ExecutionStatus.RUNNING.getCode())
                    .and(w -> w.isNull("last_history_id").or().eq("last_history_id", history.getId())));
            logger.warn("收割卡死同步执行: syncJobId={}, historyId={}, startTime={}",
                    history.getSyncJobId(), history.getId(), history.getStartTime());
        }
        return stuck.size();
    }

    /** 清理 sync_job_history + sync_job_log（created_at 早于阈值），返回删除总数 */
    @Transactional
    public int cleanupHistories(Integer retainDays) {
        int days = retainDays == null || retainDays < 1 ? DEFAULT_RETAIN_DAYS : retainDays;
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        int historyRows = syncJobHistoryMapper.delete(
                new QueryWrapper<SyncJobHistory>().lt("created_at", threshold));
        int logRows = syncJobLogMapper.delete(
                new QueryWrapper<SyncJobLog>().lt("created_at", threshold));
        logger.info("同步历史清理完成: threshold={}, historyRows={}, logRows={}", threshold, historyRows, logRows);
        return historyRows + logRows;
    }

    // ==================== 映射 ====================

    static SyncJobInfo toJobInfo(SyncJob entity) {
        if (entity == null) {
            return null;
        }
        SyncJobInfo info = new SyncJobInfo();
        info.setId(entity.getId());
        info.setName(entity.getName());
        info.setSourceDatasourceId(entity.getSourceDatasourceId());
        info.setTargetDatasourceId(entity.getTargetDatasourceId());
        info.setSourceDatabase(entity.getSourceDatabase());
        info.setSourceSchema(entity.getSourceSchema());
        info.setSourceTables(entity.getSourceTables());
        info.setSyncMode(entity.getSyncMode());
        info.setIncrementalField(entity.getIncrementalField());
        info.setTriggerType(entity.getTriggerType());
        info.setCronExpression(entity.getCronExpression());
        info.setRetryTimes(entity.getRetryTimes());
        info.setRetryInterval(entity.getRetryInterval());
        if (entity.getFieldMapping() != null) {
            info.setFieldMapping(entity.getFieldMapping().stream().map(item -> {
                FieldMappingItemDTO dto = new FieldMappingItemDTO();
                dto.setSourceColumn(item.getSourceColumn());
                dto.setTargetColumn(item.getTargetColumn());
                dto.setTargetType(item.getTargetType());
                return dto;
            }).toList());
        }
        info.setStatus(entity.getStatus());
        info.setExecutionStatus(entity.getExecutionStatus());
        info.setTargetDatabase(entity.getTargetDatabase());
        info.setTargetTable(entity.getTargetTable());
        info.setNextExecutionTime(entity.getNextExecutionTime());
        info.setScheduleEnabled(entity.getScheduleEnabled());
        info.setSourceTablesDetail(entity.getSourceTablesDetail());
        info.setReadRateLimitMbps(entity.getReadRateLimitMbps());
        info.setWriteRateLimitRowsPerSecond(entity.getWriteRateLimitRowsPerSecond());
        info.setRateLimitEnabled(entity.getRateLimitEnabled());
        info.setSchedulerJobId(entity.getSchedulerJobId());
        info.setDescription(entity.getDescription());
        info.setLastExecuteTime(entity.getLastExecuteTime());
        info.setLastHistoryId(entity.getLastHistoryId());
        info.setCreatedBy(entity.getCreatedBy());
        info.setUpdatedBy(entity.getUpdatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        info.setUpdatedAt(entity.getUpdatedAt());
        return info;
    }

    static SyncHistoryInfo toHistoryInfo(SyncJobHistory entity) {
        if (entity == null) {
            return null;
        }
        SyncHistoryInfo info = new SyncHistoryInfo();
        info.setId(entity.getId());
        info.setSyncJobId(entity.getSyncJobId());
        info.setDagExecutionId(entity.getDagExecutionId());
        info.setTriggerType(entity.getTriggerType());
        info.setStatus(entity.getStatus());
        info.setStartTime(entity.getStartTime());
        info.setEndTime(entity.getEndTime());
        info.setDurationMs(entity.getDurationMs());
        info.setSourceRows(entity.getSourceRows());
        info.setTargetRows(entity.getTargetRows());
        info.setErrorMessage(entity.getErrorMessage());
        info.setTableResults(entity.getTableResults());
        info.setParentHistoryId(entity.getParentHistoryId());
        info.setRetryCount(entity.getRetryCount());
        info.setNextRetryAt(entity.getNextRetryAt());
        info.setCreatedAt(entity.getCreatedAt());
        return info;
    }

    /** 与 SyncJobExecutorService.detectLevel 一致 */
    private static String detectLevel(String line) {
        if (line == null) {
            return "INFO";
        }
        String upper = line.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FAILED") || upper.contains("失败")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        return "INFO";
    }
}
