package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.governance.dto.CollectTaskCreateRequest;
import com.datanest.governance.dto.CollectTaskDTO;
import com.datanest.governance.dto.CollectTaskQueryRequest;
import com.datanest.governance.dto.CollectTaskUpdateRequest;
import com.datanest.governance.entity.CollectTask;
import com.datanest.governance.mapper.CollectTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class CollectTaskService {

    private static final Logger logger = LoggerFactory.getLogger(CollectTaskService.class);

    private static final String SCHEDULE_TYPE_CRON = "CRON";
    private static final String SCHEDULE_TYPE_NONE = "NONE";
    private static final String TRIGGER_TYPE_CRON = "CRON";
    private static final String TRIGGER_TYPE_MANUAL = "MANUAL";

    private final CollectTaskMapper collectTaskMapper;
    private final SchedulerService schedulerService;

    public CollectTaskService(CollectTaskMapper collectTaskMapper, SchedulerService schedulerService) {
        this.collectTaskMapper = collectTaskMapper;
        this.schedulerService = schedulerService;
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
        task.setStatus("NORMAL");
        task.setDescription(request.getDescription());
        task.setCreatedBy(currentUserId());
        task.setUpdatedBy(currentUserId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        // 注册到 XXL-JOB
        String scheduleType = TRIGGER_TYPE_CRON.equalsIgnoreCase(request.getTriggerType()) ? SCHEDULE_TYPE_CRON : SCHEDULE_TYPE_NONE;
        String cron = TRIGGER_TYPE_CRON.equalsIgnoreCase(request.getTriggerType()) ? request.getCronExpression() : "";
        Integer xxlJobId = schedulerService.registerJob(request.getName(), cron, scheduleType);
        task.setXxlJobId(xxlJobId);

        collectTaskMapper.insert(task);
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

        if (task.getXxlJobId() != null) {
            String scheduleType = TRIGGER_TYPE_CRON.equalsIgnoreCase(request.getTriggerType()) ? SCHEDULE_TYPE_CRON : SCHEDULE_TYPE_NONE;
            String cron = TRIGGER_TYPE_CRON.equalsIgnoreCase(request.getTriggerType()) ? request.getCronExpression() : "";
            schedulerService.updateJob(task.getXxlJobId(), request.getName(), cron, scheduleType);
        }

        collectTaskMapper.updateById(task);
        return toDTO(task);
    }

    @Transactional
    public void delete(Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

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
        if ("PAUSED".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "任务已暂停，无法执行");
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
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        return dto;
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }
}
