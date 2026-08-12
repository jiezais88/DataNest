package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.constant.CollectTaskStatus;
import com.datanest.common.constant.ScheduleType;
import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.alert.api.AlertApi;
import com.datanest.governance.api.dto.CollectTaskCreateInternalRequest;
import com.datanest.governance.dto.CollectTaskCreateRequest;
import com.datanest.governance.dto.CollectTaskDTO;
import com.datanest.governance.dto.CollectTaskQueryRequest;
import com.datanest.governance.dto.CollectTaskStatsDTO;
import com.datanest.governance.dto.CollectTaskUpdateRequest;
import com.datanest.common.constant.AlertConstants;
import com.datanest.governance.entity.CollectChangeDetail;
import com.datanest.governance.entity.CollectExecutionLog;
import com.datanest.governance.entity.CollectHistory;
import com.datanest.governance.entity.CollectTask;
import com.datanest.governance.mapper.CollectChangeDetailMapper;
import com.datanest.governance.mapper.CollectExecutionLogMapper;
import com.datanest.governance.mapper.CollectHistoryMapper;
import com.datanest.governance.mapper.CollectTaskMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CollectTaskService {

    private static final Logger logger = LoggerFactory.getLogger(CollectTaskService.class);



    private final CollectTaskMapper collectTaskMapper;
    private final SchedulerService schedulerService;
    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper collectExecutionLogMapper;
    private final CollectChangeDetailMapper changeDetailMapper;
    private final SystemUserApi systemUserApi;
    private final AlertApi alertApi;

    public CollectTaskService(CollectTaskMapper collectTaskMapper, SchedulerService schedulerService,
                              CollectHistoryMapper collectHistoryMapper,
                              CollectExecutionLogMapper collectExecutionLogMapper,
                              CollectChangeDetailMapper changeDetailMapper,
                              SystemUserApi systemUserApi,
                              AlertApi alertApi) {
        this.collectTaskMapper = collectTaskMapper;
        this.schedulerService = schedulerService;
        this.collectHistoryMapper = collectHistoryMapper;
        this.collectExecutionLogMapper = collectExecutionLogMapper;
        this.changeDetailMapper = changeDetailMapper;
        this.systemUserApi = systemUserApi;
        this.alertApi = alertApi;
    }

    @Transactional
    public CollectTaskDTO create(CollectTaskCreateRequest request) {
        return doCreate(request.getName(), request.getDatasourceId(), request.getScope(), request.getCollectMode(),
                request.getTriggerType(), request.getCronExpression(), request.getDescription(), currentUserId());
    }

    /**
     * 内部创建采集任务（Sprint 7 DD-09 任务模板一键创建，governance-api CollectWriteApi 契约）。
     * 与 Web 侧 create 同逻辑，差异：无登录上下文，createdBy 由调用方显式传入；字段非空在此手动校验。
     */
    @Transactional
    public Long createInternal(CollectTaskCreateInternalRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "任务名称不能为空");
        }
        if (request.getDatasourceId() == null) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "数据源不能为空");
        }
        if (request.getScope() == null || request.getScope().isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "采集范围不能为空");
        }
        if (request.getCollectMode() == null || request.getCollectMode().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "采集模式不能为空");
        }
        if (request.getTriggerType() == null || request.getTriggerType().isBlank()) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "触发方式不能为空");
        }
        Long createdBy = request.getCreatedBy() == null ? 0L : request.getCreatedBy();
        return doCreate(request.getName(), request.getDatasourceId(), request.getScope(), request.getCollectMode(),
                request.getTriggerType(), request.getCronExpression(), request.getDescription(), createdBy).getId();
    }

    private CollectTaskDTO doCreate(String name, Long datasourceId, List<String> scope, String collectMode,
                                    String triggerType, String cronExpression, String description, Long createdBy) {
        if (countByName(name) > 0) {
            throw new BusinessException(ErrorCode.TASK_NAME_EXISTS);
        }

        CollectTask task = new CollectTask();
        task.setName(name);
        task.setDatasourceId(datasourceId);
        task.setDatasourceName(""); // 执行时由采集引擎补充
        task.setScope(scope);
        task.setCollectMode(collectMode);
        task.setTriggerType(triggerType);
        task.setCronExpression(cronExpression);
        task.setStatus(CollectTaskStatus.NEVER_EXECUTED.getCode());
        task.setDescription(description);
        task.setScheduleEnabled(0);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(LocalDateTime.now());
        task.setNextExecutionTime(computeNextExecutionTime(triggerType, cronExpression));

        collectTaskMapper.insert(task);

        // 注册到 PowerJob（需要 task.getId()），默认不启动调度；
        // 远程调用移到事务提交后执行，事务内只做 DB 操作，避免回滚后在调度中心留下孤儿任务
        Long taskId = task.getId();
        String taskName = task.getName();
        ScheduleType scheduleType = ScheduleType.fromTriggerType(triggerType);
        String cron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(triggerType) ? cronExpression : "";
        runAfterCommit("注册调度任务", () -> {
            Long schedulerJobId = schedulerService.registerJob(taskId, taskName, cron, scheduleType.getCode(), false);
            // 回填 schedulerJobId（此处已在原事务外，单行更新自动提交）
            CollectTask schedulerJobUpdate = new CollectTask();
            schedulerJobUpdate.setId(taskId);
            schedulerJobUpdate.setSchedulerJobId(schedulerJobId);
            collectTaskMapper.updateById(schedulerJobUpdate);
            logger.info("Collect task registered to scheduler: id={}, name={}, schedulerJobId={}", taskId, taskName, schedulerJobId);
        });

        logger.info("Collect task created: id={}, name={}", task.getId(), task.getName());
        return toDTO(task);
    }

    @Transactional
    public CollectTaskDTO update(Long id, CollectTaskUpdateRequest request) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        if (!task.getName().equals(request.getName()) && countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.TASK_NAME_EXISTS);
        }

        task.setName(request.getName());
        task.setDatasourceId(request.getDatasourceId());
        task.setScope(request.getScope());
        task.setCollectMode(request.getCollectMode());
        task.setTriggerType(request.getTriggerType());
        task.setCronExpression(request.getCronExpression());
        task.setStatus(request.getStatus());
        task.setDescription(request.getDescription());
        task.setUpdatedBy(currentUserId());
        task.setUpdatedAt(LocalDateTime.now());
        task.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));

        collectTaskMapper.updateById(task);

        if (task.getSchedulerJobId() != null) {
            // 调度中心更新移到事务提交后执行，避免 DB 回滚后调度配置与库内状态不一致
            Long schedulerJobId = task.getSchedulerJobId();
            Long taskId = task.getId();
            String taskName = task.getName();
            ScheduleType scheduleType = ScheduleType.fromTriggerType(request.getTriggerType());
            String cron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType()) ? request.getCronExpression() : "";
            boolean start = task.getScheduleEnabled() != null && task.getScheduleEnabled() == 1;
            runAfterCommit("更新调度任务", () ->
                    schedulerService.updateJob(schedulerJobId, taskId, taskName, cron, scheduleType.getCode(), start));
        }
        return toDTO(task);
    }

    @Transactional
    public void startSchedule(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!TaskTriggerType.CRON.getCode().equalsIgnoreCase(task.getTriggerType())) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "仅 Cron 任务支持调度开关");
        }
        if (task.getSchedulerJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        Long schedulerJobId = task.getSchedulerJobId();
        task.setScheduleEnabled(1);
        task.setNextExecutionTime(computeNextExecutionTime(task.getTriggerType(), task.getCronExpression()));
        task.setUpdatedAt(LocalDateTime.now());
        task.setUpdatedBy(currentUserId());
        collectTaskMapper.updateById(task);
        // 远程启动移到事务提交后执行
        runAfterCommit("启动调度", () -> schedulerService.startJob(schedulerJobId));
        logger.info("Collect task schedule started: taskId={}", id);
    }

    @Transactional
    public void stopSchedule(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!TaskTriggerType.CRON.getCode().equalsIgnoreCase(task.getTriggerType())) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "仅 Cron 任务支持调度开关");
        }
        if (task.getSchedulerJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        Long schedulerJobId = task.getSchedulerJobId();
        task.setScheduleEnabled(0);
        task.setNextExecutionTime(null);
        task.setUpdatedAt(LocalDateTime.now());
        task.setUpdatedBy(currentUserId());
        collectTaskMapper.updateById(task);
        // 远程停止移到事务提交后执行
        runAfterCommit("停止调度", () -> schedulerService.stopJob(schedulerJobId));
        logger.info("Collect task schedule stopped: taskId={}", id);
    }

    @Transactional
    public void delete(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        // 运行中保护：RUNNING 时删除会让执行器回调更新已不存在的历史记录
        if (CollectTaskStatus.RUNNING.getCode().equalsIgnoreCase(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_RUNNING,
                    "任务正在运行中，请等待运行完成后再删除");
        }

        // 提前取出注销调度所需的信息（DB 行删除后 afterCommit 中无法再查询）
        Long schedulerJobId = task.getSchedulerJobId();

        // 级联删除：变更明细 → 执行日志 → 历史记录
        List<Long> historyIds = collectHistoryMapper.selectList(new QueryWrapper<CollectHistory>()
                        .eq("task_id", id).select("id"))
                .stream().map(CollectHistory::getId).collect(Collectors.toList());
        if (!historyIds.isEmpty()) {
            changeDetailMapper.delete(new QueryWrapper<CollectChangeDetail>().in("history_id", historyIds));
        }
        collectExecutionLogMapper.delete(new QueryWrapper<CollectExecutionLog>().eq("task_id", id));
        collectHistoryMapper.delete(new QueryWrapper<CollectHistory>().eq("task_id", id));
        collectTaskMapper.deleteById(id);
        // Sprint 5：删除采集任务时级联删除关联告警规则（PRD §7）
        // 微服务化改造：改由 alert-service 远程清理；原来同事务，现在接受最终一致，
        // 远程失败仅记 warn，不阻断主删除流程
        RemoteCalls.execute("alert.deleteRuleByObject",
                () -> alertApi.deleteRuleByObject(AlertConstants.OBJECT_TYPE_COLLECT_TASK, id));

        if (schedulerJobId != null) {
            // 注销调度移到事务提交后执行，避免 DB 回滚后在调度中心留下孤儿任务
            runAfterCommit("注销调度任务", () -> schedulerService.unregisterJob(schedulerJobId));
        }
        logger.info("Collect task deleted: id={}, name={}", id, task.getName());
    }

    /**
     * 将调度中心（PowerJob）远程调用注册为事务提交后执行（参考 DataSourceService 的 afterCommit 模式）；
     * afterCommit 中的异常只记 error 日志，不再影响已提交的数据，调度侧不一致需人工核对或后续补偿。
     */
    private void runAfterCommit(String action, Runnable schedulerCall) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    schedulerCall.run();
                } catch (Exception e) {
                    logger.error("调度中心操作失败（DB 已提交，调度侧可能不一致）: action={}", action, e);
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public CollectTaskDTO getById(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<Long, String> usernameMap = usernames(
                List.of(task.getCreatedBy(), task.getUpdatedBy()));
        return toDTO(task, usernameMap);
    }

    /**
     * 任务状态统计（列表页顶部统计卡），避免前端拉全量列表计数。
     */
    @Transactional(readOnly = true)
    public CollectTaskStatsDTO listStats() {
        CollectTaskStatsDTO stats = collectTaskMapper.selectStats();
        return stats == null ? new CollectTaskStatsDTO() : stats;
    }

    @Transactional(readOnly = true)
    public PageResult<CollectTaskDTO> list(CollectTaskQueryRequest request) {
        IPage<CollectTask> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<CollectTask> wrapper = new QueryWrapper<>();

        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim())
                    .or()
                    .like("datasource_name", request.getKeyword().trim());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq("status", request.getStatus());
        }
        if (request.getDatasourceId() != null) {
            wrapper.eq("datasource_id", request.getDatasourceId());
        }
        wrapper.orderByDesc("created_at");

        IPage<CollectTask> result = collectTaskMapper.selectPage(page, wrapper);
        List<Long> userIds = result.getRecords().stream()
                .flatMap(t -> Stream.of(t.getCreatedBy(), t.getUpdatedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = usernames(userIds);
        List<CollectTaskDTO> records = result.getRecords().stream()
                .map(t -> toDTO(t, usernameMap))
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void execute(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        if (task.getSchedulerJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        schedulerService.triggerJob(task.getSchedulerJobId(), id + ",MANUAL");
        logger.info("Collect task triggered manually: taskId={}, schedulerJobId={}", id, task.getSchedulerJobId());
    }

    public List<DataSourceReferenceDTO> getReferencesByDataSource(Long datasourceId) {
        List<CollectTask> tasks = collectTaskMapper.selectList(
                new QueryWrapper<CollectTask>().eq("datasource_id", datasourceId));
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        return tasks.stream()
                .map(t -> {
                    DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
                    dto.setTaskId(t.getId());
                    dto.setTaskName(t.getName());
                    dto.setStatus(t.getStatus());
                    return dto;
                })
                .toList();
    }

    private long countByName(String name) {
        return collectTaskMapper.selectCount(new QueryWrapper<CollectTask>().eq("name", name));
    }

    private LocalDateTime computeNextExecutionTime(String triggerType, String cronExpression) {
        if (!TaskTriggerType.CRON.getCode().equalsIgnoreCase(triggerType) || !StringUtils.hasText(cronExpression)) {
            return null;
        }
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            return cron.next(LocalDateTime.now());
        } catch (Exception e) {
            logger.warn("Invalid cron expression, cannot compute next execution time: {}", cronExpression);
            return null;
        }
    }

    private CollectTaskDTO toDTO(CollectTask task) {
        return toDTO(task, Map.of());
    }

    private CollectTaskDTO toDTO(CollectTask task, Map<Long, String> usernameMap) {
        CollectTaskDTO dto = new CollectTaskDTO();
        dto.setId(task.getId());
        dto.setName(task.getName());
        dto.setDatasourceId(task.getDatasourceId());
        dto.setDatasourceName(task.getDatasourceName());
        dto.setScope(task.getScope());
        dto.setCollectMode(task.getCollectMode());
        dto.setTriggerType(task.getTriggerType());
        dto.setCronExpression(task.getCronExpression());
        dto.setStatus(task.getStatus());
        dto.setLastExecuteTime(task.getLastExecuteTime());
        dto.setLastHistoryId(task.getLastHistoryId());
        dto.setDescription(task.getDescription());
        dto.setSchedulerJobId(task.getSchedulerJobId());
        dto.setScheduleEnabled(task.getScheduleEnabled());
        dto.setNextExecutionTime(task.getNextExecutionTime());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setCreatedBy(task.getCreatedBy());
        dto.setUpdatedBy(task.getUpdatedBy());
        // 用户名映射可能为空（create 时只设 created_by，按审计约定 updated_by 为 null），
        // 且 Map.of() 构造的不可变 map 不支持 null key 查询，需空安全取值。
        dto.setCreatedByName(lookupName(usernameMap, task.getCreatedBy()));
        dto.setUpdatedByName(lookupName(usernameMap, task.getUpdatedBy()));
        return dto;
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 空安全的用户名映射查询：userId 为 null 时直接返回 null，避免不可变 Map.get(null) 抛 NPE。 */
    private String lookupName(Map<Long, String> usernameMap, Long userId) {
        return userId == null ? null : usernameMap.get(userId);
    }

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射。
     * system 不可用时降级为空 Map 并记 warn（列表页名称列退化为空），不拖垮本接口。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常（如序列化错），warn + 计数后返回空 Map
        return RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        }, Map.of());
    }
}
