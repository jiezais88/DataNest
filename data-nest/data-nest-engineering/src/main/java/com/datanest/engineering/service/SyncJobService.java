package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.constant.SyncJobExecutionStatus;
import com.datanest.common.constant.SyncJobScheduleStatus;
import com.datanest.common.constant.SyncMode;
import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.dto.*;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SyncJobService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobService.class);

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagMapper dagMapper;
    private final SchedulerServiceForEngineering schedulerService;
    private final RetryService retryService;

    public SyncJobService(SyncJobMapper syncJobMapper, SyncJobHistoryMapper syncJobHistoryMapper,
                          SyncJobLogMapper syncJobLogMapper, DataSourceConnectionMapper dataSourceConnectionMapper,
                          DagNodeMapper dagNodeMapper, DagMapper dagMapper,
                          SchedulerServiceForEngineering schedulerService, RetryService retryService) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagMapper = dagMapper;
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
        entity.setExecutionStatus(SyncJobExecutionStatus.PENDING.getCode());
        entity.setStatus(SyncJobScheduleStatus.NORMAL.getCode());
        entity.setScheduleEnabled(0);
        entity.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.insert(entity);

        // XXL-JOB 注册放到事务提交后：DB 回滚时不会产生孤儿调度任务
        if (TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType())) {
            Long jobId = entity.getId();
            String name = entity.getName();
            String cronExpression = entity.getCronExpression();
            String triggerType = entity.getTriggerType();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        Integer xxlJobId = schedulerService.registerJob(jobId, name, cronExpression, triggerType, false);
                        SyncJob fresh = syncJobMapper.selectById(jobId);
                        if (fresh != null) {
                            fresh.setXxlJobId(xxlJobId);
                            syncJobMapper.updateById(fresh);
                        }
                    } catch (Exception e) {
                        logger.error("同步任务创建后注册 XXL-JOB 失败（不影响已提交的 DB 数据，启动调度时会重试注册）: syncJobId={}",
                                jobId, e);
                    }
                }
            });
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

        Integer oldXxlJobId = entity.getXxlJobId();
        boolean cron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType());

        copyFromRequest(entity, request);
        entity.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());

        if (!cron && oldXxlJobId != null) {
            // 切换为非 Cron：DB 先解除绑定，XXL-JOB 任务在事务提交后注销
            entity.setXxlJobId(null);
            entity.setScheduleEnabled(0);
            entity.setStatus(SyncJobScheduleStatus.NORMAL.getCode());
            entity.setNextExecutionTime(null);
        }

        syncJobMapper.updateById(entity);

        // XXL-JOB 注册/更新/注销放到事务提交后：避免 DB 回滚产生孤儿调度任务
        Long jobId = entity.getId();
        String name = entity.getName();
        String cronExpression = entity.getCronExpression();
        String triggerType = entity.getTriggerType();
        boolean start = entity.getScheduleEnabled() != null && entity.getScheduleEnabled() == 1;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (cron) {
                        if (oldXxlJobId != null) {
                            schedulerService.updateJob(oldXxlJobId, jobId, name, cronExpression, triggerType, start);
                        } else {
                            Integer newXxlJobId = schedulerService.registerJob(jobId, name, cronExpression, triggerType, start);
                            SyncJob fresh = syncJobMapper.selectById(jobId);
                            if (fresh != null) {
                                fresh.setXxlJobId(newXxlJobId);
                                syncJobMapper.updateById(fresh);
                            }
                        }
                    } else if (oldXxlJobId != null) {
                        schedulerService.unregisterJob(oldXxlJobId);
                    }
                } catch (Exception e) {
                    logger.error("同步任务更新后同步 XXL-JOB 失败（不影响已提交的 DB 数据）: syncJobId={}, xxlJobId={}",
                            jobId, oldXxlJobId, e);
                }
            }
        });
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        // 执行中保护：RUNNING 时删除会让执行器回调更新已不存在的历史记录
        if ("RUNNING".equalsIgnoreCase(entity.getExecutionStatus())) {
            throw new BusinessException(ErrorCode.SYNC_JOB_ALREADY_RUNNING,
                    "任务正在执行中，请等待执行完成后再删除");
        }
        // Sprint 3 PRD §8：同步任务被 DAG 引用时禁止删除
        // 用 PostgreSQL 正则匹配 config 中的 syncJobId，兼容空格、数值/字符串格式
        Set<Long> referencingDagIds = new HashSet<>(
                dagNodeMapper.selectDagIdsReferencingSyncJob(
                        "\"syncJobId\"\\s*:\\s*\\\"?" + id + "\\\"?"));
        if (!referencingDagIds.isEmpty()) {
            List<Dag> referencingDags = dagMapper.selectBatchIds(referencingDagIds);
            String names = referencingDags.stream()
                    .map(Dag::getName)
                    .filter(n -> n != null && !n.isEmpty())
                    .collect(Collectors.joining("、"));
            throw new BusinessException(ErrorCode.DAG_REFERENCED,
                    "该同步任务已被 DAG 引用，无法删除。引用 DAG：" + names);
        }
        syncJobLogMapper.delete(new QueryWrapper<SyncJobLog>().eq("sync_job_id", id));
        syncJobHistoryMapper.delete(new QueryWrapper<SyncJobHistory>().eq("sync_job_id", id));
        syncJobMapper.deleteById(id);

        // XXL-JOB 注销放到事务提交后：避免 DB 回滚时调度任务已被误删
        Integer xxlJobId = entity.getXxlJobId();
        if (xxlJobId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerService.unregisterJob(xxlJobId);
                    } catch (Exception e) {
                        logger.warn("删除同步任务时注销 XXL-JOB 任务失败: syncJobId={}, xxlJobId={}", id, xxlJobId, e);
                    }
                }
            });
        }
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

    public Long execute(Long id) {
        return execute(id, TaskTriggerType.MANUAL.getCode());
    }

    /**
     * 触发同步任务执行，返回生成的 sync_job_history.id。
     * DAG 回调场景通过 triggerType 区分来源。
     */
    public Long execute(Long id, String triggerType) {
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
        job.setExecutionStatus(SyncJobExecutionStatus.RUNNING.getCode());
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);

        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(id);
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);

        String param = id + "," + triggerType + "," + history.getId();
        schedulerService.triggerJob(job.getXxlJobId(), param);
        logger.info("已触发同步任务执行: syncJobId={}, historyId={}, triggerType={}, param={}",
                id, history.getId(), triggerType, param);
        return history.getId();
    }

    @Transactional
    public void startSchedule(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        if (!TaskTriggerType.CRON.getCode().equalsIgnoreCase(entity.getTriggerType())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "仅 Cron 任务可启动调度");
        }
        entity.setScheduleEnabled(1);
        entity.setStatus(SyncJobScheduleStatus.NORMAL.getCode());
        entity.setNextExecutionTime(computeNextExecutionTime(entity.getTriggerType(), entity.getCronExpression()));
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(entity);

        // XXL-JOB 注册/启动放到事务提交后：避免 DB 回滚产生孤儿调度任务
        Integer oldXxlJobId = entity.getXxlJobId();
        String name = entity.getName();
        String cronExpression = entity.getCronExpression();
        String triggerType = entity.getTriggerType();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (oldXxlJobId == null) {
                        Integer newXxlJobId = schedulerService.registerJob(id, name, cronExpression, triggerType, true);
                        SyncJob fresh = syncJobMapper.selectById(id);
                        if (fresh != null) {
                            fresh.setXxlJobId(newXxlJobId);
                            syncJobMapper.updateById(fresh);
                        }
                    } else {
                        schedulerService.startJob(oldXxlJobId);
                    }
                } catch (Exception e) {
                    logger.error("启动调度时同步 XXL-JOB 失败（不影响已提交的 DB 数据）: syncJobId={}, xxlJobId={}",
                            id, oldXxlJobId, e);
                }
            }
        });
    }

    @Transactional
    public void stopSchedule(Long id) {
        SyncJob entity = syncJobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        entity.setScheduleEnabled(0);
        entity.setStatus(SyncJobScheduleStatus.PAUSED.getCode());
        entity.setNextExecutionTime(null);
        entity.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(entity);

        // XXL-JOB 停止放到事务提交后：DB 状态已落库，调度侧失败仅记日志
        Integer xxlJobId = entity.getXxlJobId();
        if (xxlJobId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerService.stopJob(xxlJobId);
                    } catch (Exception e) {
                        logger.error("停止调度时同步 XXL-JOB 失败（不影响已提交的 DB 数据）: syncJobId={}, xxlJobId={}",
                                id, xxlJobId, e);
                    }
                }
            });
        }
    }

    public PageResult<SyncJobHistoryDTO> historyPage(Long syncJobId, SyncJobHistoryQueryRequest request) {
        IPage<SyncJobHistory> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<SyncJobHistory> wrapper = new QueryWrapper<>();

        if (syncJobId != null) {
            wrapper.eq("sync_job_id", syncJobId);
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        if (request.getStartTimeFrom() != null && request.getStartTimeTo() != null) {
            wrapper.between("start_time", request.getStartTimeFrom(), request.getStartTimeTo());
        }

        // 按任务名称模糊搜索：先查 sync_job 得到匹配 ID，再过滤历史
        List<Long> matchedJobIds = null;
        if (StringUtils.hasText(request.getKeyword())) {
            QueryWrapper<SyncJob> jobWrapper = new QueryWrapper<>();
            jobWrapper.like("name", request.getKeyword().trim());
            matchedJobIds = syncJobMapper.selectList(jobWrapper).stream()
                    .map(SyncJob::getId)
                    .distinct()
                    .toList();
            if (matchedJobIds.isEmpty()) {
                return PageResult.of(List.of(), 0L, request.getPage(), request.getPageSize());
            }
            wrapper.in("sync_job_id", matchedJobIds);
        }

        wrapper.orderByDesc("start_time");
        IPage<SyncJobHistory> result = syncJobHistoryMapper.selectPage(page, wrapper);

        // 性能优化：selectBatchIds 一次性取回页内涉及的同步任务，避免逐条 selectById 的 N+1
        List<Long> jobIds = result.getRecords().stream()
                .map(SyncJobHistory::getSyncJobId)
                .distinct()
                .toList();
        Map<Long, SyncJob> jobMap = jobIds.isEmpty()
                ? Map.of()
                : syncJobMapper.selectBatchIds(jobIds).stream()
                .collect(Collectors.toMap(SyncJob::getId, j -> j, (a, b) -> a));

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

    private LocalDateTime computeNextExecutionTime(String triggerType, String cronExpression) {
        if (!TaskTriggerType.CRON.getCode().equalsIgnoreCase(triggerType) || !StringUtils.hasText(cronExpression)) {
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
        if (TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType()) && !StringUtils.hasText(request.getCronExpression())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cron 触发方式必须填写 Cron 表达式");
        }
        if (SyncMode.INCREMENTAL.getCode().equalsIgnoreCase(request.getSyncMode()) && !StringUtils.hasText(request.getIncrementalField())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "增量同步必须填写增量字段");
        }
    }

    private void validateRequest(SyncJobUpdateRequest request) {
        if (TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType()) && !StringUtils.hasText(request.getCronExpression())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cron 触发方式必须填写 Cron 表达式");
        }
        if (SyncMode.INCREMENTAL.getCode().equalsIgnoreCase(request.getSyncMode()) && !StringUtils.hasText(request.getIncrementalField())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "增量同步必须填写增量字段");
        }
    }

    private void checkDataSource(Long sourceDatasourceId) {
        DataSourceConnection source = dataSourceConnectionMapper.selectById(sourceDatasourceId);
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
        // Sprint 3 Phase 8: 多表 + 限流
        entity.setSourceTablesDetail(parseSourceTablesDetail(request.getSourceTablesDetail()));
        entity.setReadRateLimitMbps(request.getReadRateLimitMbps() == null ? 0 : request.getReadRateLimitMbps());
        entity.setWriteRateLimitRowsPerSecond(request.getWriteRateLimitRowsPerSecond() == null ? 0 : request.getWriteRateLimitRowsPerSecond());
        entity.setRateLimitEnabled(Boolean.TRUE.equals(request.getRateLimitEnabled()) ? 1 : 0);
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
        // Sprint 3 Phase 8
        entity.setSourceTablesDetail(parseSourceTablesDetail(request.getSourceTablesDetail()));
        entity.setReadRateLimitMbps(request.getReadRateLimitMbps() == null ? 0 : request.getReadRateLimitMbps());
        entity.setWriteRateLimitRowsPerSecond(request.getWriteRateLimitRowsPerSecond() == null ? 0 : request.getWriteRateLimitRowsPerSecond());
        entity.setRateLimitEnabled(Boolean.TRUE.equals(request.getRateLimitEnabled()) ? 1 : 0);
    }

    /**
     * 解析 sourceTablesDetail 字符串为 JSONB
     * 输入：合法的 JSON 字符串
     * 输出：JSONB 字符串（保留原始 JSON 结构）
     */
    private String parseSourceTablesDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "[]";
        }
        String trimmed = detail.trim();
        if (!trimmed.startsWith("[")) {
            return "[]";
        }
        return trimmed;
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
            dto.setTaskName(job.getName());
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
