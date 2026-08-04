package com.datanest.task.core.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.dto.QualityJobCreateRequest;
import com.datanest.task.core.dto.QualityJobDTO;
import com.datanest.task.core.dto.QualityJobQueryRequest;
import com.datanest.task.core.dto.QualityJobUpdateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量任务服务（Sprint 6 配置层，D-D1）。
 * <p>
 * 任务 CRUD + 分页 + 详情（含规则列表）+ 启停 + 删除（级联删规则）+ 手动执行预留。
 * 调度状态口径（D-D1）：仅 scheduled_enabled=1 且配了 cron 显示「已启用 / 已停用」，
 * 纯手动/自动任务显示「—」；enabled 是任务整体启停，二者独立。
 */
@Service
public class QualityJobService {

    private static final Set<String> ALERT_LEVELS = Set.of("SEVERE_ONLY", "SEVERE_WARNING");

    private final QualityJobMapper jobMapper;
    private final DataSourceConnectionMapper dataSourceMapper;
    private final QualityRuleService ruleService;
    private final SysUserService sysUserService;

    public QualityJobService(QualityJobMapper jobMapper,
                             DataSourceConnectionMapper dataSourceMapper,
                             QualityRuleService ruleService,
                             SysUserService sysUserService) {
        this.jobMapper = jobMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.ruleService = ruleService;
        this.sysUserService = sysUserService;
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
        if (request.getDatasourceId() != null) {
            wrapper.eq("datasource_id", request.getDatasourceId());
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
        return dto;
    }

    /**
     * 调度扫描用：查询启用 + 开定时 + cron 非空的任务。
     */
    public List<QualityJob> listScheduledEnabled() {
        return jobMapper.selectList(new QueryWrapper<QualityJob>()
                .eq("enabled", 1)
                .eq("scheduled_enabled", 1)
                .isNotNull("cron")
                .ne("cron", ""));
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
        entity.setDatasourceId(request.getDatasourceId());
        entity.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
        entity.setScheduledEnabled(request.getScheduledEnabled() == null ? 0 : request.getScheduledEnabled());
        entity.setCron(request.getCron());
        entity.setAutoTriggerEnabled(request.getAutoTriggerEnabled() == null ? 0 : request.getAutoTriggerEnabled());
        entity.setAutoTriggerObjectType(request.getAutoTriggerObjectType());
        entity.setAutoTriggerObjectId(request.getAutoTriggerObjectId());
        entity.setAlertLevel(request.getAlertLevel() == null ? "SEVERE_WARNING" : request.getAlertLevel());
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        jobMapper.insert(entity);
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

        // 更新语义：可清空字段（description/datasource_id）无论是否 null 都更新（传 null 即清空）；
        // 其余字段 null 不更新。
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id);
        wrapper.set("name", name);
        wrapper.set("description", request.getDescription());
        wrapper.set("datasource_id", request.getDatasourceId());
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
        return getById(id);
    }

    /**
     * 删除任务（级联删除其下所有规则）。
     */
    @Transactional
    public void delete(Long id) {
        requireJob(id);
        ruleService.deleteByJob(id);
        jobMapper.deleteById(id);
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
     * 手动执行任务（预留）：执行校验下一批实现。
     */
    public void executeJob(Long id) {
        requireJob(id);
        throw new BusinessException(ErrorCode.QUALITY_RULE_EXECUTE_NOT_IMPLEMENTED, "执行功能待实现");
    }

    /**
     * 记录最近触发时间（定时扫描 handler 调用）。
     * 仅更新 last_trigger_at/updated_at，避免全字段 UPDATE（定时扫描可能每分钟命中）。
     */
    public void touchLastTriggerAt(Long id) {
        requireJob(id);
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<QualityJob> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("last_trigger_at", now)
                .set("updated_at", now);
        jobMapper.update(null, wrapper);
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
     * 批量构建 DTO，一次性回填数据源名/用户名/规则数，避免 N+1。
     */
    private List<QualityJobDTO> buildDTOs(List<QualityJob> records, boolean withRuleCount) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        // 数据源名
        Set<Long> dsIds = records.stream()
                .map(QualityJob::getDatasourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, DataSourceConnection> dsMap = dsIds.isEmpty()
                ? Map.of() : dataSourceMapper.selectBatchIds(dsIds).stream()
                .collect(Collectors.toMap(DataSourceConnection::getId, Function.identity()));
        // 用户名
        Map<Long, String> usernameMap = loadUsernameMap(records);
        // 规则数（仅列表需要；详情单独查）
        Map<Long, Long> ruleCountMap = withRuleCount && !records.isEmpty()
                ? loadRuleCounts(records) : Map.of();

        return records.stream().map(e -> toDTO(e, dsMap, usernameMap, ruleCountMap)).toList();
    }

    private QualityJobDTO toDTO(QualityJob entity, Map<Long, DataSourceConnection> dsMap,
                                Map<Long, String> usernameMap, Map<Long, Long> ruleCountMap) {
        QualityJobDTO dto = new QualityJobDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setDatasourceId(entity.getDatasourceId());
        DataSourceConnection ds = entity.getDatasourceId() == null ? null : dsMap.get(entity.getDatasourceId());
        dto.setDatasourceName(ds == null ? null : ds.getName());
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
