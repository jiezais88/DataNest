package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.alert.api.AlertApi;
import com.datanest.engineering.api.EngineeringObjectApi;
import com.datanest.engineering.api.dto.ObjectNameRequest;
import com.datanest.common.constant.AlertConstants;
import com.datanest.task.core.dto.QualityJobCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.datanest.task.core.dto.QualityJobDTO;
import com.datanest.task.core.dto.QualityJobQueryRequest;
import com.datanest.task.core.dto.QualityJobUpdateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.entity.QualityJobRule;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.entity.QualityScore;
import com.datanest.governance.mapper.CollectTaskMapper;
import com.datanest.governance.mapper.QualityJobMapper;
import com.datanest.governance.mapper.QualityJobRuleMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import com.datanest.governance.mapper.QualityScoreMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
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
    private final SystemUserApi systemUserApi;
    private final QualityCheckTriggerService triggerService;
    private final SchedulerClient schedulerClient;
    private final AlertApi alertApi;
    private final QualityJobRuleMapper qualityJobRuleMapper;
    private final QualityRuleMapper qualityRuleMapper;
    private final QualityScoreMapper qualityScoreMapper;
    private final CollectTaskMapper collectTaskMapper;
    private final EngineeringObjectApi engineeringObjectApi;

    public QualityJobService(QualityJobMapper jobMapper,
                             QualityRuleService ruleService,
                             SystemUserApi systemUserApi,
                             QualityCheckTriggerService triggerService,
                             SchedulerClient schedulerClient,
                             AlertApi alertApi,
                             QualityJobRuleMapper qualityJobRuleMapper,
                             QualityRuleMapper qualityRuleMapper,
                             QualityScoreMapper qualityScoreMapper,
                             CollectTaskMapper collectTaskMapper,
                             EngineeringObjectApi engineeringObjectApi) {
        this.jobMapper = jobMapper;
        this.ruleService = ruleService;
        this.systemUserApi = systemUserApi;
        this.triggerService = triggerService;
        this.schedulerClient = schedulerClient;
        this.alertApi = alertApi;
        this.qualityJobRuleMapper = qualityJobRuleMapper;
        this.qualityRuleMapper = qualityRuleMapper;
        this.qualityScoreMapper = qualityScoreMapper;
        this.collectTaskMapper = collectTaskMapper;
        this.engineeringObjectApi = engineeringObjectApi;
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
        entity.setTimeoutMinutes(request.getTimeoutMinutes());
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(entity);
        // 绑定引用的质量规则（多对多）
        ruleService.setJobRules(entity.getId(), request.getRuleIds());
        // 创建即开启定时调度：scheduledEnabled=1 且配置了 cron 时，事务内同步注册 XXL-JOB；
        // 注册失败抛错 → 事务回滚（任务不落库），前端可见错误，避免「任务在但 XXL-JOB 没注册」的不一致。
        boolean scheduleOnCreate = entity.getScheduledEnabled() != null && entity.getScheduledEnabled() == 1
                && StringUtils.hasText(entity.getCron());
        if (scheduleOnCreate) {
            registerSchedule(entity);
        }
        return getById(entity.getId());
    }

    /**
     * 为任务注册/启动独立的 XXL-JOB（worker 组，带自身 cron），并写回 xxl_job_id。
     * 事务内同步调用（不再 afterCommit 静默吞异常）：注册失败抛 BusinessException 使事务回滚，保证 DB 与调度一致。
     */
    private void registerSchedule(QualityJob entity) {
        Integer xxlJobId = schedulerClient.registerJob(workerAppName, HANDLER_NAME, entity.getId(),
                entity.getName(), entity.getCron(), TRIGGER_TYPE_CRON, true, 0, 0);
        jobMapper.update(null, new UpdateWrapper<QualityJob>()
                .eq("id", entity.getId()).set("xxl_job_id", xxlJobId));
        logger.info("注册质量任务定时调度: jobId={}, xxlJobId={}, cron={}", entity.getId(), xxlJobId, entity.getCron());
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
        // timeoutMinutes：null 不更新（保留原值）；传值（包含 0=禁用）则覆盖
        if (request.getTimeoutMinutes() != null) {
            wrapper.set("timeout_minutes", request.getTimeoutMinutes());
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
        // 删除关联校验：若任务已被告警规则绑定（对象类型 QUALITY），阻止删除，并返回具体告警规则名称
        // 微服务化改造：引用校验经 alert-service 远程查询；远程不可用时失败关闭（阻止删除并提示重试），
        // 避免跳过校验误删仍被引用的任务
        List<String> alertRuleNames;
        try {
            Result<List<String>> alertResult = alertApi.listRuleNamesByObject(AlertConstants.OBJECT_TYPE_QUALITY, id);
            alertRuleNames = alertResult == null || alertResult.data() == null ? List.of() : alertResult.data();
        } catch (Exception e) {
            logger.error("质量任务告警规则引用远程校验失败: jobId={}", id, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "告警规则引用校验失败，请稍后重试");
        }
        if (!alertRuleNames.isEmpty()) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES,
                    "质量任务已被告警规则引用，请先删除相关告警规则", alertRuleNames);
        }
        // 删除前收集本任务涉及的规则/表（用于删除后评分处理），随后再解除 job_rule 关联
        List<Long> ruleIds = qualityJobRuleMapper.selectList(
                        new QueryWrapper<QualityJobRule>().eq("job_id", id))
                .stream().map(QualityJobRule::getRuleId).toList();
        List<Long> tableIds = Collections.emptyList();
        if (!ruleIds.isEmpty()) {
            tableIds = qualityRuleMapper.selectBatchIds(ruleIds).stream()
                    .map(QualityRule::getTableId).distinct().toList();
        }
        ruleService.deleteJobRules(id);
        jobMapper.deleteById(id);
        // 评分处理（方案1）：任务删除后，若其涉及的表不再有任何启用规则覆盖，则删除该表评分；
        // 仍有启用规则的表保留评分（下次检查批次收尾会重算）。质量检查批次/明细作为审计记录保留（用户确认）。
        if (!tableIds.isEmpty()) {
            cleanupScoresWithoutActiveRules(tableIds);
        }

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
     * 评分处理（删任务后）：对指定表，若该表不再有任何启用规则覆盖，则删除其质量评分。
     * 产品语义：评分反映「当前仍被质量监控的表」的健康度；不再被监控的表应恢复"无质量"态。
     * 仍有启用规则的表保留评分（下次检查批次收尾由 ScoreCalculator 重算）。
     */
    private void cleanupScoresWithoutActiveRules(List<Long> tableIds) {
        for (Long tableId : tableIds) {
            Long activeRules = qualityRuleMapper.selectCount(
                    new QueryWrapper<QualityRule>().eq("table_id", tableId).eq("enabled", 1));
            if (activeRules == null || activeRules == 0) {
                int removed = qualityScoreMapper.delete(
                        new QueryWrapper<QualityScore>().eq("table_id", tableId));
                if (removed > 0) {
                    logger.info("删除质量任务后清理无规则覆盖表的评分: tableId={}, removed={}", tableId, removed);
                }
            }
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
     * cron 为空时抛错；注册/启动在事务内同步执行——失败抛 BusinessException 使事务回滚（scheduled_enabled 不置 1），
     * 保证 DB 与 XXL-JOB 一致且前端可见错误。
     */
    @Transactional
    public void startSchedule(Long id) {
        QualityJob entity = requireJob(id);
        if (!StringUtils.hasText(entity.getCron())) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_CRON_REQUIRED, "未配置 Cron 表达式，无法开启调度");
        }
        Integer oldXxlJobId = entity.getXxlJobId();
        if (oldXxlJobId == null) {
            // 同步注册（失败抛错 → 事务回滚，scheduled_enabled 不置 1）
            registerSchedule(entity);
        } else {
            schedulerClient.startJob(oldXxlJobId);
        }
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("scheduled_enabled", 1)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        jobMapper.update(null, wrapper);
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
        // 回填自动触发绑定对象名（同步任务/DAG 节点/采集任务），便于列表/详情直观展示绑定关系
        dto.setAutoTriggerObjectName(resolveAutoTriggerObjectName(entity));
        dto.setAlertLevel(entity.getAlertLevel());
        dto.setTimeoutMinutes(entity.getTimeoutMinutes());
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

    /**
     * 解析自动触发绑定对象名称：DAG_NODE → dag.name；SYNC_JOB → sync_job.name；COLLECT_TASK → collect_task.name。
     * 微服务化 3.3：sync_job/dag 名称经 EngineeringObjectApi.names 远程查询（RemoteCalls 降级 null，
     * 名称列退化为空，不拖垮列表）；collect_task 为治理域表，本阶段仍本地读取。
     */
    private String resolveAutoTriggerObjectName(QualityJob job) {
        if (job.getAutoTriggerObjectId() == null) {
            return null;
        }
        String type = job.getAutoTriggerObjectType();
        Long objectId = job.getAutoTriggerObjectId();
        try {
            if ("SYNC_JOB".equals(type)) {
                return remoteObjectName("SYNC_JOB", objectId);
            }
            if ("COLLECT_TASK".equals(type)) {
                var obj = collectTaskMapper.selectById(objectId);
                return obj == null ? null : obj.getName();
            }
            if ("DAG_NODE".equals(type)) {
                return remoteObjectName("DAG", objectId);
            }
        } catch (Exception e) {
            logger.warn("解析质量任务自动触发对象名失败: jobId={}, type={}, objectId={}", job.getId(), type, objectId, e);
        }
        return null;
    }

    /** 经 engineering 内部接口按对象类型批量查名称（单 id 调用，取回 map 中的值） */
    private String remoteObjectName(String objectType, Long objectId) {
        return RemoteCalls.execute("engineering.object.names", () -> {
            ObjectNameRequest request = new ObjectNameRequest();
            request.setObjectType(objectType);
            request.setIds(List.of(objectId));
            Result<Map<Long, String>> result = engineeringObjectApi.names(request);
            return result == null || result.data() == null ? null : result.data().get(objectId);
        }, null);
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
        return usernames(userIds);
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
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
