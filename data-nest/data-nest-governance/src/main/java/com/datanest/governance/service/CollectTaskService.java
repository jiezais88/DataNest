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
import com.datanest.governance.dto.CollectTaskCreateRequest;
import com.datanest.governance.dto.CollectTaskDTO;
import com.datanest.governance.dto.CollectTaskQueryRequest;
import com.datanest.governance.dto.CollectTaskUpdateRequest;
import com.datanest.task.core.entity.CollectChangeDetail;
import com.datanest.task.core.entity.CollectExecutionLog;
import com.datanest.task.core.entity.CollectHistory;
import com.datanest.task.core.entity.CollectTask;
import com.datanest.task.core.mapper.CollectChangeDetailMapper;
import com.datanest.task.core.mapper.CollectExecutionLogMapper;
import com.datanest.task.core.mapper.CollectHistoryMapper;
import com.datanest.task.core.mapper.CollectTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CollectTaskService {

    private static final Logger logger = LoggerFactory.getLogger(CollectTaskService.class);



    private final CollectTaskMapper collectTaskMapper;
    private final SchedulerService schedulerService;
    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper collectExecutionLogMapper;
    private final CollectChangeDetailMapper changeDetailMapper;

    public CollectTaskService(CollectTaskMapper collectTaskMapper, SchedulerService schedulerService,
                              CollectHistoryMapper collectHistoryMapper,
                              CollectExecutionLogMapper collectExecutionLogMapper,
                              CollectChangeDetailMapper changeDetailMapper) {
        this.collectTaskMapper = collectTaskMapper;
        this.schedulerService = schedulerService;
        this.collectHistoryMapper = collectHistoryMapper;
        this.collectExecutionLogMapper = collectExecutionLogMapper;
        this.changeDetailMapper = changeDetailMapper;
    }

    @Transactional
    public CollectTaskDTO create(CollectTaskCreateRequest request) {
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.TASK_NAME_EXISTS);
        }

        CollectTask task = new CollectTask();
        task.setName(request.getName());
        task.setDatasourceId(request.getDatasourceId());
        task.setDatasourceName(""); // 执行时由采集引擎补充
        task.setScope(request.getScope());
        task.setCollectMode(request.getCollectMode());
        task.setTriggerType(request.getTriggerType());
        task.setCronExpression(request.getCronExpression());
        task.setStatus(CollectTaskStatus.NEVER_EXECUTED.getCode());
        task.setDescription(request.getDescription());
        task.setScheduleEnabled(0);
        task.setCreatedBy(currentUserId());
        task.setUpdatedBy(currentUserId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setNextExecutionTime(computeNextExecutionTime(request.getTriggerType(), request.getCronExpression()));

        collectTaskMapper.insert(task);

        // 注册到 XXL-JOB（需要 task.getId()），默认不启动调度
        ScheduleType scheduleType = ScheduleType.fromTriggerType(request.getTriggerType());
        String cron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType()) ? request.getCronExpression() : "";
        Integer xxlJobId = schedulerService.registerJob(task.getId(), request.getName(), cron, scheduleType.getCode(), false);
        task.setXxlJobId(xxlJobId);
        collectTaskMapper.updateById(task);

        logger.info("Collect task created: id={}, name={}, xxlJobId={}", task.getId(), task.getName(), xxlJobId);
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

        if (task.getXxlJobId() != null) {
            ScheduleType scheduleType = ScheduleType.fromTriggerType(request.getTriggerType());
            String cron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(request.getTriggerType()) ? request.getCronExpression() : "";
            boolean start = task.getScheduleEnabled() != null && task.getScheduleEnabled() == 1;
            schedulerService.updateJob(task.getXxlJobId(), task.getId(), request.getName(), cron, scheduleType.getCode(), start);
        }

        collectTaskMapper.updateById(task);
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
        if (task.getXxlJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        schedulerService.startJob(task.getXxlJobId());
        task.setScheduleEnabled(1);
        task.setNextExecutionTime(computeNextExecutionTime(task.getTriggerType(), task.getCronExpression()));
        task.setUpdatedAt(LocalDateTime.now());
        task.setUpdatedBy(currentUserId());
        collectTaskMapper.updateById(task);
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
        if (task.getXxlJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        schedulerService.stopJob(task.getXxlJobId());
        task.setScheduleEnabled(0);
        task.setNextExecutionTime(null);
        task.setUpdatedAt(LocalDateTime.now());
        task.setUpdatedBy(currentUserId());
        collectTaskMapper.updateById(task);
        logger.info("Collect task schedule stopped: taskId={}", id);
    }

    @Transactional
    public void delete(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        // 级联删除：变更明细 → 执行日志 → 历史记录
        List<Long> historyIds = collectHistoryMapper.selectList(new QueryWrapper<CollectHistory>()
                        .eq("task_id", id).select("id"))
                .stream().map(CollectHistory::getId).collect(Collectors.toList());
        if (!historyIds.isEmpty()) {
            changeDetailMapper.delete(new QueryWrapper<CollectChangeDetail>().in("history_id", historyIds));
        }
        collectExecutionLogMapper.delete(new QueryWrapper<CollectExecutionLog>().eq("task_id", id));
        collectHistoryMapper.delete(new QueryWrapper<CollectHistory>().eq("task_id", id));

        if (task.getXxlJobId() != null) {
            schedulerService.unregisterJob(task.getXxlJobId());
        }
        collectTaskMapper.deleteById(id);
        logger.info("Collect task deleted: id={}, name={}", id, task.getName());
    }

    @Transactional(readOnly = true)
    public CollectTaskDTO getById(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        return toDTO(task);
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
        List<CollectTaskDTO> records = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void execute(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        if (task.getXxlJobId() == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务未注册到调度中心");
        }
        schedulerService.triggerJob(task.getXxlJobId(), id + ",MANUAL");
        logger.info("Collect task triggered manually: taskId={}, xxlJobId={}", id, task.getXxlJobId());
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
        dto.setXxlJobId(task.getXxlJobId());
        dto.setScheduleEnabled(task.getScheduleEnabled());
        dto.setNextExecutionTime(task.getNextExecutionTime());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        return dto;
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
