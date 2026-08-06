package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.constant.*;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.alert.api.AlertApi;
import com.datanest.engineering.dto.*;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.SourceTableDetail;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import com.datanest.task.core.service.SyncJobTriggerService;
import com.datanest.task.core.service.SyncNodeMutexService;
import com.datanest.task.core.service.SysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SyncJobService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobService.class);
    private static final ZoneId APP_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final SchedulerServiceForEngineering schedulerService;
    private final SyncJobTriggerService syncJobTriggerService;
    private final SyncNodeMutexService syncNodeMutexService;
    private final SysUserService sysUserService;
    private final AlertApi alertApi;

    public SyncJobService(SyncJobMapper syncJobMapper, SyncJobHistoryMapper syncJobHistoryMapper,
                          SyncJobLogMapper syncJobLogMapper, DataSourceConnectionMapper dataSourceConnectionMapper,
                          DagNodeMapper dagNodeMapper, DagMapper dagMapper, DagExecutionMapper dagExecutionMapper,
                          SchedulerServiceForEngineering schedulerService,
                          SyncJobTriggerService syncJobTriggerService,
                          SyncNodeMutexService syncNodeMutexService, SysUserService sysUserService,
                          AlertApi alertApi) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagMapper = dagMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.schedulerService = schedulerService;
        this.syncJobTriggerService = syncJobTriggerService;
        this.syncNodeMutexService = syncNodeMutexService;
        this.sysUserService = sysUserService;
        this.alertApi = alertApi;
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
        entity.setCreatedAt(LocalDateTime.now());
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
            List<String> names = referencingDags.stream()
                    .map(Dag::getName)
                    .filter(n -> n != null && !n.isEmpty())
                    .toList();
            throw new BusinessException(ErrorCode.DAG_REFERENCED,
                    "该同步任务已被 DAG 引用，无法删除", names);
        }
        syncJobLogMapper.delete(new QueryWrapper<SyncJobLog>().eq("sync_job_id", id));
        syncJobHistoryMapper.delete(new QueryWrapper<SyncJobHistory>().eq("sync_job_id", id));
        syncJobMapper.deleteById(id);
        // Sprint 5：删除同步任务时级联删除关联告警规则（PRD §7）
        // 微服务化改造：改由 alert-service 远程清理；原来同事务，现在接受最终一致，
        // 远程失败仅记 warn，不阻断主删除流程
        try {
            alertApi.deleteRuleByObject(AlertConstants.OBJECT_TYPE_SYNC_JOB, id);
        } catch (Exception e) {
            logger.warn("同步任务告警规则远程级联删除失败（接受最终一致，不阻断删除）: syncJobId={}", id, e);
        }

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
        SyncJobDTO dto = toDTO(entity);
        fillUsernameNames(List.of(dto));
        return dto;
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
        fillUsernameNames(records);
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public Long execute(Long id) {
        return execute(id, TaskTriggerType.MANUAL.getCode(), null);
    }

    /**
     * 触发同步任务执行，返回生成的 sync_job_history.id。
     * DAG 回调场景通过 triggerType 区分来源。
     */
    public Long execute(Long id, String triggerType) {
        return execute(id, triggerType, null);
    }

    /**
     * 触发同步任务执行并记录来源 DAG 执行实例。
     */
    public Long execute(Long id, String triggerType, Long dagExecutionId) {
        return syncJobTriggerService.triggerSyncJob(id, triggerType, dagExecutionId);
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

    /**
     * 手动停止同步执行历史（协作式停止）。
     * worker 线程阻塞在 Addax 子进程的 readLine()，无法走 XXL logKill/interrupt，
     * 因此这里只把 DB 置为 TERMINATED，由 worker 侧 watcher 轮询发现后 destroy 子进程。
     */
    public void stopHistory(Long historyId) {
        SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND, "同步执行历史不存在");
        }
        // 幂等：非 RUNNING 说明已收尾（成功/失败/已被停止），直接返回
        if (!ExecutionStatus.RUNNING.getCode().equalsIgnoreCase(history.getStatus())) {
            return;
        }
        // 条件更新防并发：只有仍处于 RUNNING 才置 TERMINATED；
        // 更新 0 行说明已被 worker 收尾并发写掉，无需再改任务状态/放锁
        LocalDateTime now = LocalDateTime.now();
        Long durationMs = history.getStartTime() != null
                ? Duration.between(history.getStartTime(), now).toMillis()
                : null;
        int updated = syncJobHistoryMapper.update(null,
                new UpdateWrapper<SyncJobHistory>()
                        .eq("id", historyId)
                        .eq("status", ExecutionStatus.RUNNING.getCode())
                        .set("status", ExecutionStatus.TERMINATED.getCode())
                        .set("end_time", now)
                        .set("duration_ms", durationMs));
        if (updated == 0) {
            return;
        }
        Long syncJobId = history.getSyncJobId();
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job != null) {
            job.setExecutionStatus(SyncJobExecutionStatus.TERMINATED.getCode());
            job.setUpdatedAt(LocalDateTime.now());
            syncJobMapper.updateById(job);
        }
        // 主动放锁：否则互斥锁要等 watcher 收尾或兜底轮询才释放，阻塞下一次执行
        try {
            syncNodeMutexService.unlockBySyncJobId(syncJobId);
        } catch (Exception e) {
            logger.warn("手动停止后释放同步互斥锁失败: syncJobId={}, historyId={}", syncJobId, historyId, e);
        }
        logger.info("已手动停止同步执行: syncJobId={}, historyId={}", syncJobId, historyId);
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
        LocalDateTime startTimeFrom = parseIsoToLocalDateTime(request.getStartTimeFrom());
        LocalDateTime startTimeTo = parseIsoToLocalDateTime(request.getStartTimeTo());
        if (startTimeFrom != null && startTimeTo != null) {
            wrapper.between("start_time", startTimeFrom, startTimeTo);
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

        // DAG 来源信息：一次性取回页内涉及的 dag_execution 与 dag，避免 N+1
        List<Long> dagExecutionIds = result.getRecords().stream()
                .map(SyncJobHistory::getDagExecutionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, DagExecution> dagExecutionMap = dagExecutionIds.isEmpty()
                ? Map.of()
                : dagExecutionMapper.selectBatchIds(dagExecutionIds).stream()
                .collect(Collectors.toMap(DagExecution::getId, e -> e, (a, b) -> a));
        List<Long> dagIds = dagExecutionMap.values().stream()
                .map(DagExecution::getDagId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Dag> dagMap = dagIds.isEmpty()
                ? Map.of()
                : dagMapper.selectBatchIds(dagIds).stream()
                .collect(Collectors.toMap(Dag::getId, d -> d, (a, b) -> a));

        List<SyncJobHistoryDTO> records = result.getRecords().stream()
                .map(h -> toHistoryDTO(h, jobMap.get(h.getSyncJobId()), dagExecutionMap, dagMap))
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 按历史 ID 返回全部日志（SYNC 节点执行详情用，不分页）。
     */
    public List<SyncJobLogDTO> getLogs(Long historyId) {
        QueryWrapper<SyncJobLog> wrapper = new QueryWrapper<SyncJobLog>()
                .eq("history_id", historyId)
                .orderByAsc("line_num", "created_at");
        return syncJobLogMapper.selectList(wrapper).stream().map(this::toLogDTO).toList();
    }

    public PageResult<SyncJobLogDTO> getLogs(Long historyId, String scope, int page, int pageSize) {
        QueryWrapper<SyncJobLog> wrapper = new QueryWrapper<SyncJobLog>()
                .eq("history_id", historyId)
                .orderByAsc("line_num", "created_at");
        if ("overview".equalsIgnoreCase(scope)) {
            // 平台概要行（开始/成功/失败），table_name 为 NULL
            wrapper.isNull("table_name");
        } else if (StringUtils.hasText(scope) && !"all".equalsIgnoreCase(scope)) {
            wrapper.eq("table_name", scope);
        }
        Page<SyncJobLog> result = syncJobLogMapper.selectPage(Page.of(page, pageSize), wrapper);
        List<SyncJobLogDTO> records = result.getRecords().stream().map(this::toLogDTO).toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
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
        // Sprint 4：多表映射与限流字段暴露给前端
        if (StringUtils.hasText(entity.getSourceTablesDetail())) {
            try {
                dto.setSourceTablesDetail(JSON.parseArray(entity.getSourceTablesDetail(), SourceTableDetail.class));
            } catch (Exception e) {
                logger.warn("解析 sourceTablesDetail 失败: syncJobId={}", entity.getId(), e);
            }
        }
        dto.setReadRateLimitMbps(entity.getReadRateLimitMbps());
        dto.setWriteRateLimitRowsPerSecond(entity.getWriteRateLimitRowsPerSecond());
        dto.setRateLimitEnabled(entity.getRateLimitEnabled() != null && entity.getRateLimitEnabled() == 1);
        dto.setNextExecutionTime(entity.getNextExecutionTime());
        dto.setScheduleEnabled(entity.getScheduleEnabled() != null && entity.getScheduleEnabled() == 1);
        dto.setXxlJobId(entity.getXxlJobId());
        dto.setDescription(entity.getDescription());
        dto.setLastExecuteTime(entity.getLastExecuteTime());
        dto.setLastHistoryId(entity.getLastHistoryId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }

    private void fillUsernameNames(List<SyncJobDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        List<Long> userIds = dtos.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getCreatedBy(), d.getUpdatedBy()))
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = sysUserService.getUsernameMap(userIds);
        for (SyncJobDTO dto : dtos) {
            if (dto.getCreatedBy() != null && dto.getCreatedBy() > 0) {
                dto.setCreatedByName(usernameMap.getOrDefault(dto.getCreatedBy(), "-"));
            }
            if (dto.getUpdatedBy() != null && dto.getUpdatedBy() > 0) {
                dto.setUpdatedByName(usernameMap.getOrDefault(dto.getUpdatedBy(), "-"));
            }
        }
    }

    private SyncJobHistoryDTO toHistoryDTO(SyncJobHistory entity, SyncJob job,
                                           Map<Long, DagExecution> dagExecutionMap, Map<Long, Dag> dagMap) {
        SyncJobHistoryDTO dto = new SyncJobHistoryDTO();
        dto.setId(entity.getId());
        dto.setSyncJobId(entity.getSyncJobId());
        dto.setDagExecutionId(entity.getDagExecutionId());
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
            dto.setSourceTables(job.getSourceTables());
            dto.setTableResults(parseTableResults(entity.getTableResults()));
            dto.setSyncMode(job.getSyncMode());
            dto.setIncrementalField(job.getIncrementalField());
        }

        // DAG 来源：带出 dagId/dagName，前端可点击跳到对应 DAG 执行实例
        if (entity.getDagExecutionId() != null) {
            DagExecution dagExecution = dagExecutionMap.get(entity.getDagExecutionId());
            if (dagExecution != null && dagExecution.getDagId() != null) {
                dto.setDagId(dagExecution.getDagId());
                Dag dag = dagMap.get(dagExecution.getDagId());
                if (dag != null) {
                    dto.setDagName(dag.getName());
                }
            }
        }
        return dto;
    }

    private List<SyncTableResultDTO> parseTableResults(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return JSON.parseArray(text, SyncTableResultDTO.class);
        } catch (Exception e) {
            logger.warn("解析 sync_job_history.table_results 失败: {}", e.getMessage());
            return null;
        }
    }

    private SyncJobLogDTO toLogDTO(SyncJobLog entity) {
        SyncJobLogDTO dto = new SyncJobLogDTO();
        dto.setId(entity.getId());
        dto.setHistoryId(entity.getHistoryId());
        dto.setSyncJobId(entity.getSyncJobId());
        dto.setLevel(entity.getLevel());
        dto.setMessage(entity.getMessage());
        dto.setLineNum(entity.getLineNum());
        dto.setTableName(entity.getTableName());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    /**
     * 把 ISO 字符串解析为 LocalDateTime（容器时区 Asia/Shanghai）。
     * 兼容 "2026-08-02T12:00:00Z"（UTC）和 "2026-08-02T12:00:00"（无时区）。
     * null/空 视为无界；非法格式抛 INTERNAL_ERROR。
     */
    private LocalDateTime parseIsoToLocalDateTime(String iso) {
        if (!StringUtils.hasText(iso)) {
            return null;
        }
        String trimmed = iso.trim();
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(APP_TIME_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignore) {
            try {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "时间格式非法：" + trimmed + "，期望 ISO 8601 格式");
            }
        }
    }
}
