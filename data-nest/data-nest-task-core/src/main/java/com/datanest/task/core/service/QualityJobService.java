package com.datanest.task.core.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.task.core.dto.QualityJobCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.datanest.task.core.dto.QualityJobDTO;
import com.datanest.task.core.dto.QualityJobQueryRequest;
import com.datanest.task.core.dto.QualityJobUpdateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.mapper.QualityJobMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量任务服务（Sprint 6 配置层 + Sprint 8 执行层调度）。
 * <p>
 * 任务 CRUD + 分页 + 详情（含规则列表）+ 启停 + 删除（级联删规则）+ 执行触发。
 * Sprint 8：执行经 {@link QualityCheckTriggerService} 投递到 worker 异步执行；
 * 定时调度改为「每任务独立注册 XXL-JOB（worker 组，带自身 cron）」，startSchedule 注册/启动、
 * stopSchedule 仅停止、delete 注销，与同步任务调度模型一致（不再用全局扫描）。
 */
@Service
public class QualityJobService {

    private static final Logger logger = LoggerFactory.getLogger(QualityJobService.class);

    private static final Set<String> ALERT_LEVELS = Set.of("SEVERE_ONLY", "SEVERE_WARNING");

    private static final String HANDLER_NAME = "qualityCheckExecuteHandler";
    private static final String TRIGGER_TYPE_CRON = "CRON";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String workerAppName;

    private final QualityJobMapper jobMapper;
    private final QualityRuleService ruleService;
    private final SysUserService sysUserService;
    private final QualityCheckTriggerService triggerService;
    private final SchedulerClient schedulerClient;

    public QualityJobService(QualityJobMapper jobMapper,
                             QualityRuleService ruleService,
                             SysUserService sysUserService,
                             QualityCheckTriggerService triggerService,
                             SchedulerClient schedulerClient) {
        this.jobMapper = jobMapper;
        this.ruleService = ruleService;
        this.sysUserService = sysUserService;
        this.triggerService = triggerService;
        this.schedulerClient = schedulerClient;
    }

    // ==================== 查询 ====================

    /**
     * 分页列表（含规则数、调度状态徽章）。
     */
    public PageResult<QualityJobDTO> list(QualityJobQueryRequest request) {
        IPage<QualityJob> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<QualityJob> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.and(w -> w.like("name", request.getKeyword().trim())
                    .or().like("description", request.getKeyword().trim()));
        }
        if (request.getEnabled() != null) {
            wrapper.eq("enabled", request.getEnabled());
        }
        if (request.getScheduledEnabled() != null) {
            wrapper.eq("scheduled_enabled", request.getScheduledEnabled());
        }
        wrapper.orderByDesc("id");
        IPage<QualityJob> result = jobMapper.selectPage(page, wrapper);

        List<QualityJobDTO> dtos = buildDTOs(result.getRecords(), true);
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 任务详情（含规则列表）。
     */
    public QualityJobDTO getById(Long id) {
        QualityJob entity = requireJob(id);
        QualityJobDTO dto = buildDTOs(List.of(entity), false).get(0);
        List<QualityRuleDTO> rules = ruleService.listByJob(id);
        dto.setRules(rules);
        // 详情带规则列表，ruleCount 需与列表一致（buildDTOs(false) 时 ruleCount 恒 0，此处重算）
        dto.setRuleCount((long) rules.size());
        // 回填引用的规则 ID 集合（供前端编辑回显，Sprint 7）
        dto.setRuleIds(rules.stream().map(QualityRuleDTO::getId).toList());
        return dto;
    }

    // ==================== 写操作 ====================

    @Transactional
    public QualityJobDTO create(QualityJobCreateRequest request) {
        String name = request.getName().trim();
        if (countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NAME_EXISTS, "质量任务名称已存在: " + name);
        }
        validateAlertLevel(request.getAlertLevel());

        QualityJob entity = new QualityJob();
        entity.setName(name);
        entity.setDescription(request.getDescription());
        entity.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
        entity.setScheduledEnabled(request.getScheduledEnabled() == null ? 0 : request.getScheduledEnabled());
        entity.setCron(request.getCron());
        entity.setAutoTriggerEnabled(request.getAutoTriggerEnabled() == null ? 0 : request.getAutoTriggerEnabled());
        entity.setAutoTriggerObjectType(request.getAutoTriggerObjectType());
        entity.setAutoTriggerObjectId(request.getAutoTriggerObjectId());
        entity.setAlertLevel(request.getAlertLevel() == null ? "SEVERE_WARNING" : request.getAlertLevel());
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(entity);
        // 绑定引用的质量规则（多对多）
        ruleService.setJobRules(entity.getId(), request.getRuleIds());
        return getById(entity.getId());
    }

    @Transactional
    public QualityJobDTO update(Long id, QualityJobUpdateRequest request) {
        QualityJob entity = requireJob(id);
        String name = request.getName().trim();
        if (!entity.getName().equals(name) && countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NAME_EXISTS, "质量任务名称已存在: " + name);
        }
        if (request.getAlertLevel() != null) {
            validateAlertLevel(request.getAlertLevel());
        }

        // 更新语义：description 可清空（传 null 即清空）；其余字段 null 不更新。
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id);
        wrapper.set("name", name);
        wrapper.set("description", request.getDescription());
        if (request.getEnabled() != null) {
            wrapper.set("enabled", request.getEnabled());
        }
        if (request.getScheduledEnabled() != null) {
            wrapper.set("scheduled_enabled", request.getScheduledEnabled());
        }
        if (request.getCron() != null) {
            wrapper.set("cron", request.getCron());
        }
        if (request.getAutoTriggerEnabled() != null) {
            wrapper.set("auto_trigger_enabled", request.getAutoTriggerEnabled());
        }
        if (request.getAutoTriggerObjectType() != null) {
            wrapper.set("auto_trigger_object_type", request.getAutoTriggerObjectType());
        }
        if (request.getAutoTriggerObjectId() != null) {
            wrapper.set("auto_trigger_object_id", request.getAutoTriggerObjectId());
        }
        if (request.getAlertLevel() != null) {
            wrapper.set("alert_level", request.getAlertLevel());
        }
        wrapper.set("updated_by", currentUserId());
        wrapper.set("updated_at", LocalDateTime.now());
        jobMapper.update(null, wrapper);
        // 全量覆盖引用的质量规则关联（Sprint 7 多对多）
        if (request.getRuleIds() != null) {
            ruleService.setJobRules(id, request.getRuleIds());
        }

        // Sprint 8：cron 变更且任务已注册 XXL-JOB 时，事务提交后同步调度 cron（参照同步任务）
        Integer oldXxlJobId = entity.getXxlJobId();
        boolean cronChanged = request.getCron() != null
                && !request.getCron().equals(entity.getCron());
        if (oldXxlJobId != null && cronChanged) {
            String newCron = request.getCron();
            String newName = name;
            boolean scheduleEnabled = request.getScheduledEnabled() != null
                    ? request.getScheduledEnabled() == 1
                    : entity.getScheduledEnabled() != null && entity.getScheduledEnabled() == 1;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerClient.updateJob(oldXxlJobId, workerAppName, HANDLER_NAME, id, newName,
                                newCron, TRIGGER_TYPE_CRON, scheduleEnabled, 0, 0);
                    } catch (Exception e) {
                        logger.warn("更新调度 cron 失败（不影响已提交的 DB 数据）: jobId={}", id, e);
                    }
                }
            });
        }
        return getById(id);
    }

    /**
     * 删除任务（Sprint 7：仅删任务的规则关联，规则本身保留可被其他任务引用）。
     * Sprint 8：删除时注销任务注册的 XXL-JOB（事务提交后），避免孤儿调度任务。
     */
    @Transactional
    public void delete(Long id) {
        QualityJob entity = requireJob(id);
        ruleService.deleteJobRules(id);
        jobMapper.deleteById(id);

        Integer xxlJobId = entity.getXxlJobId();
        if (xxlJobId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerClient.unregisterJob(xxlJobId);
                    } catch (Exception e) {
                        logger.warn("删除质量任务时注销 XXL-JOB 失败: jobId={}", id, e);
                    }
                }
            });
        }
    }

    /**
     * 启停任务。
     */
    @Transactional
    public QualityJobDTO toggle(Long id, Boolean enabled) {
        QualityJob entity = requireJob(id);
        boolean target = enabled != null ? enabled : (entity.getEnabled() == null || entity.getEnabled() != 1);
        entity.setEnabled(target ? 1 : 0);
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(entity);
        return buildDTOs(List.of(entity), false).get(0);
    }

    /**
     * 手动执行任务：触发 worker 上的质量执行 XXL-JOB 异步执行。
     */
    public void executeJob(Long id) {
        requireJob(id);
        triggerService.triggerJob(id, "MANUAL");
    }

    /**
     * 开启调度（scheduled_enabled=1）：为任务注册/启动一个独立的 XXL-JOB（worker 组，带自身 cron）。
     * cron 为空时抛错；注册/启动放在事务提交后，避免 DB 回滚产生孤儿调度任务。
     */
    @Transactional
    public void startSchedule(Long id) {
        QualityJob entity = requireJob(id);
        if (!StringUtils.hasText(entity.getCron())) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_CRON_REQUIRED, "未配置 Cron 表达式，无法开启调度");
        }
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("scheduled_enabled", 1)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        jobMapper.update(null, wrapper);

        Integer oldXxlJobId = entity.getXxlJobId();
        String name = entity.getName();
        String cron = entity.getCron();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (oldXxlJobId == null) {
                        Integer newXxlJobId = schedulerClient.registerJob(workerAppName, HANDLER_NAME, id, name,
                                cron, TRIGGER_TYPE_CRON, true, 0, 0);
                        jobMapper.update(null, new UpdateWrapper<QualityJob>()
                                .eq("id", id).set("xxl_job_id", newXxlJobId));
                    } else {
                        schedulerClient.startJob(oldXxlJobId);
                    }
                } catch (Exception e) {
                    logger.warn("开启调度时注册/启动 XXL-JOB 失败（不影响已提交的 DB 数据）: jobId={}", id, e);
                }
            }
        });
    }

    /**
     * 关闭调度（scheduled_enabled=0）：仅停止 XXL-JOB（stopJob），不注销，保留 xxl_job_id 便于快速恢复。
     */
    @Transactional
    public void stopSchedule(Long id) {
        QualityJob entity = requireJob(id);
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("scheduled_enabled", 0)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        jobMapper.update(null, wrapper);

        Integer xxlJobId = entity.getXxlJobId();
        if (xxlJobId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerClient.stopJob(xxlJobId);
                    } catch (Exception e) {
                        logger.warn("停止调度时停止 XXL-JOB 失败（不影响已提交的 DB 数据）: jobId={}", id, e);
                    }
                }
            });
        }
    }

    // ==================== private ====================

    private QualityJob requireJob(Long id) {
        QualityJob entity = jobMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NOT_FOUND, "质量任务不存在: " + id);
        }
        return entity;
    }

    private void validateAlertLevel(String alertLevel) {
        if (alertLevel != null && !ALERT_LEVELS.contains(alertLevel)) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_ALERT_LEVEL_INVALID,
                    "告警等级非法（仅支持 SEVERE_ONLY / SEVERE_WARNING）");
        }
    }

    private long countByName(String name) {
        return jobMapper.selectCount(new QueryWrapper<QualityJob>().eq("name", name));
    }

    private String resolveScheduleBadge(QualityJob entity) {
        boolean scheduled = entity.getScheduledEnabled() != null && entity.getScheduledEnabled() == 1
                && StringUtils.hasText(entity.getCron());
        if (!scheduled) {
            return "—";
        }
        boolean active = entity.getEnabled() == null || entity.getEnabled() == 1;
        return active ? "已启用" : "已停用";
    }

    /**
     * 批量构建 DTO，一次性回填用户名/规则数，避免 N+1。
     */
    private List<QualityJobDTO> buildDTOs(List<QualityJob> records, boolean withRuleCount) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        // 用户名
        Map<Long, String> usernameMap = loadUsernameMap(records);
        // 规则数（仅列表需要；详情单独查）
        Map<Long, Long> ruleCountMap = withRuleCount && !records.isEmpty()
                ? loadRuleCounts(records) : Map.of();

        return records.stream().map(e -> toDTO(e, usernameMap, ruleCountMap)).toList();
    }

    private QualityJobDTO toDTO(QualityJob entity, Map<Long, String> usernameMap, Map<Long, Long> ruleCountMap) {
        QualityJobDTO dto = new QualityJobDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setEnabled(entity.getEnabled());
        dto.setScheduledEnabled(entity.getScheduledEnabled());
        dto.setCron(entity.getCron());
        dto.setAutoTriggerEnabled(entity.getAutoTriggerEnabled());
        dto.setAutoTriggerObjectType(entity.getAutoTriggerObjectType());
        dto.setAutoTriggerObjectId(entity.getAutoTriggerObjectId());
        dto.setAlertLevel(entity.getAlertLevel());
        dto.setLastTriggerAt(entity.getLastTriggerAt());
        dto.setScheduleStatusBadge(resolveScheduleBadge(entity));
        dto.setRuleCount(ruleCountMap.getOrDefault(entity.getId(), 0L));
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setCreatedByName(entity.getCreatedBy() == null ? null : usernameMap.get(entity.getCreatedBy()));
        dto.setUpdatedByName(entity.getUpdatedBy() == null ? null : usernameMap.get(entity.getUpdatedBy()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private Map<Long, Long> loadRuleCounts(List<QualityJob> records) {
        List<Long> jobIds = records.stream().map(QualityJob::getId).collect(Collectors.toList());
        return ruleService.countByJobIds(jobIds);
    }

    private Map<Long, String> loadUsernameMap(List<QualityJob> records) {
        Set<Long> userIds = records.stream()
                .flatMap(e -> Stream.of(e.getCreatedBy(), e.getUpdatedBy()))
                .filter(Objects::nonNull).filter(id -> id > 0)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return sysUserService.getUsernameMap(userIds);
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
