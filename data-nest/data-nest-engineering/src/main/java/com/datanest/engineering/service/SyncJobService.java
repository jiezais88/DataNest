package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.entity.DataSourceConnection;
import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.entity.SyncJobHistory;
import com.datanest.engineering.entity.SyncJobLog;
import com.datanest.engineering.mapper.DataSourceMapper;
import com.datanest.engineering.mapper.SyncJobHistoryMapper;
import com.datanest.engineering.mapper.SyncJobLogMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SyncJobService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobService.class);
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String EXECUTION_STATUS_PENDING = "PENDING";
    private static final String EXECUTION_STATUS_RUNNING = "RUNNING";
    private static final String EXECUTION_STATUS_SUCCESS = "SUCCESS";
    private static final String EXECUTION_STATUS_FAILED = "FAILED";
    private static final String TRIGGER_CRON = "CRON";
    private static final String TRIGGER_MANUAL = "MANUAL";

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;
    private final DataSourceMapper dataSourceMapper;
    private final AddaxJobService addaxJobService;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SchedulerServiceForEngineering schedulerService;
    private final RetryService retryService;

    public SyncJobService(SyncJobMapper syncJobMapper, SyncJobHistoryMapper syncJobHistoryMapper,
                          SyncJobLogMapper syncJobLogMapper, DataSourceMapper dataSourceMapper,
                          AddaxJobService addaxJobService, MetadataRegistrationService metadataRegistrationService,
                          SchedulerServiceForEngineering schedulerService, RetryService retryService) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.addaxJobService = addaxJobService;
        this.metadataRegistrationService = metadataRegistrationService;
        this.schedulerService = schedulerService;
        this.retryService = retryService;
    }

    @Transactional
    public SyncJobDTO create(SyncJobCreateRequest request) {
        validateRequest(request);
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NAME_EXISTS);
        }
        checkDataSource(request.getSourceDatasourceId());

        SyncJob entity = new SyncJob();
        copyFromRequest(entity, request);
        entity.setExecutionStatus(EXECUTION_STATUS_PENDING);
        entity.setStatus(STATUS_NORMAL);
        entity.setScheduleEnabled(0);
        entity.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.insert(entity);

        if (TRIGGER_CRON.equalsIgnoreCase(request.getTriggerType())) {
            Integer jobId = schedulerService.registerJob(entity.getId(), entity.getName(),
                    entity.getCronExpression(), entity.getTriggerType(), false);
            entity.setXxlJobId(jobId);
            syncJobMapper.updateById(entity);
        }

        return toDTO(entity);
    }

    @Transactional
    public SyncJobDTO update(Long id, SyncJobUpdateRequest request) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        validateRequest(request);
        if (!entity.getName().equals(request.getName()) && countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NAME_EXISTS);
        }
        checkDataSource(request.getSourceDatasourceId());

        copyFromRequest(entity, request);
        entity.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());

        if (TRIGGER_CRON.equalsIgnoreCase(request.getTriggerType())) {
            if (entity.getXxlJobId() != null) {
                schedulerService.updateJob(entity.getXxlJobId(), entity.getId(), entity.getName(),
                        entity.getCronExpression(), entity.getTriggerType(), entity.getScheduleEnabled() == 1);
            } else {
                Integer jobId = schedulerService.registerJob(entity.getId(), entity.getName(),
                        entity.getCronExpression(), entity.getTriggerType(), entity.getScheduleEnabled() == 1);
                entity.setXxlJobId(jobId);
            }
        } else if (entity.getXxlJobId() != null) {
            schedulerService.unregisterJob(entity.getXxlJobId());
            entity.setXxlJobId(null);
            entity.setScheduleEnabled(0);
            entity.setStatus(STATUS_NORMAL);
            entity.setNextExecutionTime(null);
        }

        syncJobMapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        syncJobLogMapper.delete(new QueryWrapper<SyncJobLog>().eq("sync_job_id", id));
        syncJobHistoryMapper.delete(new QueryWrapper<SyncJobHistory>().eq("sync_job_id", id));
        if (entity.getXxlJobId() != null) {
            try {
                schedulerService.unregisterJob(entity.getXxlJobId());
            } catch (Exception e) {
                logger.warn("删除同步任务时注销 XXL-JOB 任务失败: syncJobId={}, xxlJobId={}", id, entity.getXxlJobId(), e);
            }
        }
        syncJobMapper.deleteById(id);
    }

    public SyncJobDTO getById(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        return toDTO(entity);
    }

    public PageResult<SyncJobDTO> list(SyncJobQueryRequest request) {
        IPage<SyncJob> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<SyncJob> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.like("name", keyword).or().like("description", keyword);
        }
        // 列表状态列展示 execution_status，因此按执行状态筛选
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("execution_status", request.getStatus());
        }
        if (StringUtils.hasText(request.getTriggerType())) {
            wrapper.eq("trigger_type", request.getTriggerType());
        }
        wrapper.orderByDesc("created_at");

        IPage<SyncJob> result = syncJobMapper.selectPage(page, wrapper);
        List<SyncJobDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void execute(Long id) {
        SyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        // 手动任务在创建时不会注册 XXL-JOB，执行前按需注册
        if (job.getXxlJobId() == null) {
            Integer jobId = schedulerService.registerJob(job.getId(), job.getName(),
                    job.getCronExpression(), job.getTriggerType(), false);
            job.setXxlJobId(jobId);
            syncJobMapper.updateById(job);
        }
        job.setExecutionStatus(EXECUTION_STATUS_RUNNING);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);

        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(id);
        history.setTriggerType(TRIGGER_MANUAL);
        history.setStatus("RUNNING");
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);

        String param = id + "," + TRIGGER_MANUAL + "," + history.getId();
        schedulerService.triggerJob(job.getXxlJobId(), param);
        logger.info("已触发同步任务手动执行: syncJobId={}, historyId={}, param={}", id, history.getId(), param);
    }

    @Transactional
    public void startSchedule(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        if (!TRIGGER_CRON.equalsIgnoreCase(entity.getTriggerType())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "仅 Cron 任务可启动调度");
        }
        if (entity.getXxlJobId() == null) {
            Integer jobId = schedulerService.registerJob(entity.getId(), entity.getName(),
                    entity.getCronExpression(), entity.getTriggerType(), true);
            entity.setXxlJobId(jobId);
        } else {
            schedulerService.startJob(entity.getXxlJobId());
        }
        entity.setScheduleEnabled(1);
        entity.setStatus(STATUS_NORMAL);
        entity.setNextExecutionTime(computeNextExecutionTime(entity.getTriggerType(), entity.getCronExpression()));
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(entity);
    }

    @Transactional
    public void stopSchedule(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        if (entity.getXxlJobId() != null) {
            schedulerService.stopJob(entity.getXxlJobId());
        }
        entity.setScheduleEnabled(0);
        entity.setStatus(STATUS_PAUSED);
        entity.setNextExecutionTime(null);
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(entity);
    }

    public PageResult<SyncJobHistoryDTO> historyPage(Long syncJobId, SyncJobHistoryQueryRequest request) {
        IPage<SyncJobHistory> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<SyncJobHistory> wrapper = new QueryWrapper<>();
        wrapper.eq("sync_job_id", syncJobId);
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        if (request.getStartTimeFrom() != null && request.getStartTimeTo() != null) {
            wrapper.between("start_time", request.getStartTimeFrom(), request.getStartTimeTo());
        }
        wrapper.orderByDesc("start_time");
        IPage<SyncJobHistory> result = syncJobHistoryMapper.selectPage(page, wrapper);

        Map<Long, SyncJob> jobMap = result.getRecords().stream()
                .map(SyncJobHistory::getSyncJobId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> syncJobMapper.selectById(id),
                        (a, b) -> a
                ));

        List<SyncJobHistoryDTO> records = result.getRecords().stream()
                .map(h -> toHistoryDTO(h, jobMap.get(h.getSyncJobId())))
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public List<SyncJobLogDTO> getLogs(Long historyId) {
        List<SyncJobLog> logs = syncJobLogMapper.selectList(
                new QueryWrapper<SyncJobLog>().eq("history_id", historyId).orderByAsc("line_num", "created_at"));
        return logs.stream().map(this::toLogDTO).toList();
    }

    public void runSyncJob(Long syncJobId, String triggerType, Long historyId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
        if (history == null) {
            history = initHistory(syncJobId, triggerType);
            historyId = history.getId();
        }

        updateExecutionStatus(job, EXECUTION_STATUS_RUNNING);
        logInfo(history, "开始 Addax 同步执行, syncJobId=" + syncJobId + ", triggerType=" + triggerType);

        AddaxJobService.AddaxExecutionResult result = addaxJobService.execute(syncJobId);
        writeLogLines(history, result.logLines());

        if (result.success()) {
            finishHistory(history, result, "SUCCESS");
            updateExecutionStatus(job, EXECUTION_STATUS_SUCCESS);
            updateJobLastExecute(job, history.getId());
            metadataRegistrationService.register(syncJobId);
            logInfo(history, "同步成功，已注册 Doris 元数据");
            return;
        }

        logError(history, "Addax 执行失败: " + result.errorMessage());
        int retryTimes = job.getRetryTimes() == null ? 0 : job.getRetryTimes();
        int retryInterval = job.getRetryInterval() == null ? 0 : job.getRetryInterval();
        int currentRetryCount = history.getRetryCount() == null ? 0 : history.getRetryCount();

        if (retryInterval > 0 && currentRetryCount < retryTimes) {
            logInfo(history, "准备 " + retryInterval + " 分钟后第 " + (currentRetryCount + 1) + " 次重试");
            finishHistory(history, result, "FAILED");
            updateExecutionStatus(job, EXECUTION_STATUS_FAILED);
            updateJobLastExecute(job, history.getId());
            retryService.scheduleRetry(syncJobId, historyId, retryInterval);
            return;
        }

        finishHistory(history, result, "FAILED");
        String finalError = "同步任务最终失败" + (currentRetryCount > 0 ? "，已重试 " + currentRetryCount + " 次" : "");
        updateHistoryError(history, finalError);
        updateExecutionStatus(job, EXECUTION_STATUS_FAILED);
        updateJobLastExecute(job, history.getId());
        logError(history, finalError);
    }

    private SyncJobHistory initHistory(Long syncJobId, String triggerType) {
        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(syncJobId);
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);
        return history;
    }

    private void updateJobLastExecute(SyncJob job, Long historyId) {
        job.setLastExecuteTime(LocalDateTime.now());
        job.setLastHistoryId(historyId);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    private void finishHistory(SyncJobHistory history, AddaxJobService.AddaxExecutionResult result, String status) {
        LocalDateTime now = LocalDateTime.now();
        history.setStatus(status);
        history.setEndTime(now);
        if (history.getStartTime() != null) {
            history.setDurationMs(java.time.Duration.between(history.getStartTime(), now).toMillis());
        }
        if (result != null) {
            history.setSourceRows(result.readRows());
            history.setTargetRows(result.writeRows());
        }
        syncJobHistoryMapper.updateById(history);
    }

    private void updateHistoryError(SyncJobHistory history, String errorMessage) {
        history.setErrorMessage(errorMessage);
        syncJobHistoryMapper.updateById(history);
    }

    private void writeLogLines(SyncJobHistory history, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int lineNum = nextLineNum(history.getId());
        LocalDateTime now = LocalDateTime.now();
        for (String line : lines) {
            SyncJobLog log = new SyncJobLog();
            log.setHistoryId(history.getId());
            log.setSyncJobId(history.getSyncJobId());
            log.setLevel(detectLevel(line));
            log.setMessage(line);
            log.setLineNum(lineNum++);
            log.setCreatedAt(now);
            syncJobLogMapper.insert(log);
        }
    }

    private String detectLevel(String line) {
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

    private void logInfo(SyncJobHistory history, String message) {
        insertLog(history, "INFO", message);
    }

    private void logError(SyncJobHistory history, String message) {
        insertLog(history, "ERROR", message);
    }

    private void insertLog(SyncJobHistory history, String level, String message) {
        SyncJobLog log = new SyncJobLog();
        log.setHistoryId(history.getId());
        log.setSyncJobId(history.getSyncJobId());
        log.setLevel(level);
        log.setMessage(message);
        log.setLineNum(nextLineNum(history.getId()));
        log.setCreatedAt(LocalDateTime.now());
        syncJobLogMapper.insert(log);
    }

    private int nextLineNum(Long historyId) {
        return (int) (syncJobLogMapper.selectCount(
                new QueryWrapper<SyncJobLog>().eq("history_id", historyId)) + 1L);
    }

    private void updateExecutionStatus(SyncJob job, String executionStatus) {
        job.setExecutionStatus(executionStatus);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    private LocalDateTime computeNextExecutionTime(String triggerType, String cronExpression) {
        if (!TRIGGER_CRON.equalsIgnoreCase(triggerType) || !StringUtils.hasText(cronExpression)) {
            return null;
        }
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            return cron.next(LocalDateTime.now());
        } catch (Exception e) {
            logger.warn("解析 Cron 表达式失败: {}", cronExpression, e);
            return null;
        }
    }

    private void validateRequest(SyncJobCreateRequest request) {
        if (TRIGGER_CRON.equalsIgnoreCase(request.getTriggerType()) && !StringUtils.hasText(request.getCronExpression())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cron 触发方式必须填写 Cron 表达式");
        }
        if ("INCREMENTAL".equalsIgnoreCase(request.getSyncMode()) && !StringUtils.hasText(request.getIncrementalField())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "增量同步必须填写增量字段");
        }
    }

    private void validateRequest(SyncJobUpdateRequest request) {
        if (TRIGGER_CRON.equalsIgnoreCase(request.getTriggerType()) && !StringUtils.hasText(request.getCronExpression())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cron 触发方式必须填写 Cron 表达式");
        }
        if ("INCREMENTAL".equalsIgnoreCase(request.getSyncMode()) && !StringUtils.hasText(request.getIncrementalField())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "增量同步必须填写增量字段");
        }
    }

    private void checkDataSource(Long sourceDatasourceId) {
        DataSourceConnection source = dataSourceMapper.selectById(sourceDatasourceId);
        if (source == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "源数据源不存在: " + sourceDatasourceId);
        }
    }

    private void copyFromRequest(SyncJob entity, SyncJobCreateRequest request) {
        entity.setName(request.getName());
        entity.setSourceDatasourceId(request.getSourceDatasourceId());
        entity.setSourceDatabase(request.getSourceDatabase());
        entity.setSourceSchema(request.getSourceSchema());
        entity.setSourceTables(request.getSourceTables());
        entity.setSyncMode(request.getSyncMode());
        entity.setIncrementalField(request.getIncrementalField());
        entity.setTriggerType(request.getTriggerType());
        entity.setCronExpression(request.getCronExpression());
        entity.setRetryTimes(request.getRetryTimes());
        entity.setRetryInterval(request.getRetryInterval());
        entity.setFieldMapping(request.getFieldMapping());
        entity.setTargetDatabase(request.getTargetDatabase());
        entity.setTargetTable(request.getTargetTable());
        entity.setDescription(request.getDescription());
    }

    private void copyFromRequest(SyncJob entity, SyncJobUpdateRequest request) {
        entity.setName(request.getName());
        entity.setSourceDatasourceId(request.getSourceDatasourceId());
        entity.setSourceDatabase(request.getSourceDatabase());
        entity.setSourceSchema(request.getSourceSchema());
        entity.setSourceTables(request.getSourceTables());
        entity.setSyncMode(request.getSyncMode());
        entity.setIncrementalField(request.getIncrementalField());
        entity.setTriggerType(request.getTriggerType());
        entity.setCronExpression(request.getCronExpression());
        entity.setRetryTimes(request.getRetryTimes());
        entity.setRetryInterval(request.getRetryInterval());
        entity.setFieldMapping(request.getFieldMapping());
        entity.setTargetDatabase(request.getTargetDatabase());
        entity.setTargetTable(request.getTargetTable());
        entity.setDescription(request.getDescription());
    }

    private long countByName(String name) {
        return syncJobMapper.selectCount(new QueryWrapper<SyncJob>().eq("name", name));
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private SyncJobDTO toDTO(SyncJob entity) {
        SyncJobDTO dto = new SyncJobDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setSourceDatasourceId(entity.getSourceDatasourceId());
        dto.setSourceDatabase(entity.getSourceDatabase());
        dto.setSourceSchema(entity.getSourceSchema());
        dto.setSourceTables(entity.getSourceTables());
        dto.setSyncMode(entity.getSyncMode());
        dto.setIncrementalField(entity.getIncrementalField());
        dto.setTriggerType(entity.getTriggerType());
        dto.setCronExpression(entity.getCronExpression());
        dto.setRetryTimes(entity.getRetryTimes());
        dto.setRetryInterval(entity.getRetryInterval());
        dto.setFieldMapping(entity.getFieldMapping());
        dto.setStatus(entity.getStatus());
        dto.setExecutionStatus(entity.getExecutionStatus());
        dto.setTargetDatabase(entity.getTargetDatabase());
        dto.setTargetTable(entity.getTargetTable());
        dto.setNextExecutionTime(entity.getNextExecutionTime());
        dto.setScheduleEnabled(entity.getScheduleEnabled() != null && entity.getScheduleEnabled() == 1);
        dto.setXxlJobId(entity.getXxlJobId());
        dto.setDescription(entity.getDescription());
        dto.setLastExecuteTime(entity.getLastExecuteTime());
        dto.setLastHistoryId(entity.getLastHistoryId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private SyncJobHistoryDTO toHistoryDTO(SyncJobHistory entity, SyncJob job) {
        SyncJobHistoryDTO dto = new SyncJobHistoryDTO();
        dto.setId(entity.getId());
        dto.setSyncJobId(entity.getSyncJobId());
        dto.setTriggerType(entity.getTriggerType());
        dto.setStatus(entity.getStatus());
        dto.setStartTime(entity.getStartTime());
        dto.setEndTime(entity.getEndTime());
        dto.setDurationMs(entity.getDurationMs());
        if (entity.getDurationMs() != null) {
            dto.setDurationSeconds(entity.getDurationMs() / 1000);
        }
        dto.setSourceRows(entity.getSourceRows());
        dto.setTargetRows(entity.getTargetRows());
        if (dto.getDurationSeconds() != null && dto.getDurationSeconds() > 0 && dto.getTargetRows() != null) {
            dto.setThroughputRowsPerSecond(dto.getTargetRows().doubleValue() / dto.getDurationSeconds());
        }
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setParentHistoryId(entity.getParentHistoryId());
        dto.setRetryCount(entity.getRetryCount());
        dto.setNextRetryAt(entity.getNextRetryAt());
        dto.setCreatedAt(entity.getCreatedAt());

        if (job != null) {
            dto.setSourceDatabase(job.getSourceDatabase());
            dto.setSourceSchema(job.getSourceSchema());
            dto.setSourceTable(job.getSourceTables() == null || job.getSourceTables().isEmpty() ? null : job.getSourceTables().get(0));
            dto.setTargetDatabase(job.getTargetDatabase());
            dto.setTargetTable(job.getTargetTable());
            dto.setSyncMode(job.getSyncMode());
            dto.setIncrementalField(job.getIncrementalField());
        }
        return dto;
    }

    private SyncJobLogDTO toLogDTO(SyncJobLog entity) {
        SyncJobLogDTO dto = new SyncJobLogDTO();
        dto.setId(entity.getId());
        dto.setHistoryId(entity.getHistoryId());
        dto.setSyncJobId(entity.getSyncJobId());
        dto.setLevel(entity.getLevel());
        dto.setMessage(entity.getMessage());
        dto.setLineNum(entity.getLineNum());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
