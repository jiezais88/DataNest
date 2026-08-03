package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.AlertObjectOptionDTO;
import com.datanest.task.core.dto.AlertRuleDTO;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 通用告警规则服务（CRUD + 历史 + 对象选择）。
 * Sprint 5 决策：收件人改为平台用户（alert_rule_user 存 user_id，发送时反查 sys_user.email）。
 * 本服务不依赖 MailService，便于 system-service 通过 @Import 引入而无需邮件依赖；
 * 实际发邮件逻辑在 {@link AlertFiringService}。
 */
@Service
public class AlertRuleService {

    private static final Set<String> SUPPORTED_TRIGGERS =
            Set.of(AlertConstants.ALERT_FAILURE, AlertConstants.ALERT_TIMEOUT, AlertConstants.ALERT_SUCCESS);

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRuleUserMapper alertRuleUserMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final DagMapper dagMapper;
    private final SyncJobMapper syncJobMapper;
    private final CollectTaskMapper collectTaskMapper;

    public AlertRuleService(AlertRuleMapper alertRuleMapper,
                            AlertRuleUserMapper alertRuleUserMapper,
                            AlertHistoryMapper alertHistoryMapper,
                            DagMapper dagMapper,
                            SyncJobMapper syncJobMapper,
                            CollectTaskMapper collectTaskMapper) {
        this.alertRuleMapper = alertRuleMapper;
        this.alertRuleUserMapper = alertRuleUserMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.dagMapper = dagMapper;
        this.syncJobMapper = syncJobMapper;
        this.collectTaskMapper = collectTaskMapper;
    }

    // ==================== CRUD ====================

    public AlertRuleDTO getRule(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        AlertRuleDTO dto = toDTO(rule);
        dto.setUserIds(alertRuleUserMapper.selectUserIdsByRuleId(id));
        return dto;
    }

    public PageResult<AlertRuleDTO> listRules(int page, int pageSize, String objectType, String keyword) {
        IPage<AlertRule> p = alertRuleMapper.selectRulePage(new Page<>(page, pageSize), objectType, keyword);
        List<AlertRule> records = p.getRecords();
        Map<Long, List<Long>> usersByRule = records.isEmpty()
                ? Collections.emptyMap() : loadUserIdsByRuleIds(records.stream().map(AlertRule::getId).toList());
        List<AlertRuleDTO> dtos = records.stream()
                .map(rule -> {
                    AlertRuleDTO dto = toDTO(rule);
                    dto.setUserIds(usersByRule.getOrDefault(rule.getId(), Collections.emptyList()));
                    return dto;
                })
                .toList();
        return new PageResult<>(dtos, p.getTotal(), page, pageSize);
    }

    @Transactional
    public AlertRuleDTO createRule(AlertRuleDTO dto) {
        validate(dto);
        LocalDateTime now = LocalDateTime.now();
        AlertRule rule = new AlertRule();
        applyFields(rule, dto);
        rule.setObjectName(resolveObjectName(dto.getObjectType(), dto.getObjectId()));
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        alertRuleMapper.insert(rule);
        setRuleUsers(rule.getId(), dto.getUserIds());
        return getRule(rule.getId());
    }

    @Transactional
    public AlertRuleDTO updateRule(Long id, AlertRuleDTO dto) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        if (dto.getObjectType() != null) {
            dto.setObjectType(dto.getObjectType().toUpperCase());
        }
        applyFields(rule, dto);
        if (StringUtils.hasText(dto.getObjectType()) && dto.getObjectId() != null) {
            // 对象变化时重新解析对象名，保证列表展示一致
            rule.setObjectName(resolveObjectName(dto.getObjectType(), dto.getObjectId()));
        }
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        if (dto.getUserIds() != null) {
            setRuleUsers(id, dto.getUserIds());
        }
        return getRule(id);
    }

    @Transactional
    public void deleteRule(Long id) {
        if (alertRuleMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        alertRuleMapper.deleteById(id);
        alertRuleUserMapper.deleteByRuleId(id);
    }

    @Transactional
    public void toggleRule(Long id, Boolean enabled) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        rule.setEnabled(Boolean.TRUE.equals(enabled) ? 1 : 0);
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
    }

    // ==================== 接收用户 ====================

    public List<Long> getRuleUsers(Long id) {
        return alertRuleUserMapper.selectUserIdsByRuleId(id);
    }

    @Transactional
    public void setRuleUsers(Long id, List<Long> userIds) {
        alertRuleUserMapper.deleteByRuleId(id);
        List<Long> distinct = userIds == null ? Collections.emptyList()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        List<AlertRuleUser> list = distinct.stream().map(uid -> {
            AlertRuleUser aru = new AlertRuleUser();
            aru.setId(IdWorker.getId());
            aru.setAlertRuleId(id);
            aru.setUserId(uid);
            return aru;
        }).toList();
        alertRuleUserMapper.insertBatch(list);
    }

    // ==================== 对象选择 / 快捷入口 ====================

    /**
     * 新增规则时可选对象下拉。
     */
    public List<AlertObjectOptionDTO> listObjectOptions(String objectType) {
        if (AlertConstants.OBJECT_TYPE_DAG.equalsIgnoreCase(objectType)) {
            return dagMapper.selectList(new QueryWrapper<Dag>().select("id", "name"))
                    .stream().map(d -> new AlertObjectOptionDTO(d.getId(), d.getName())).toList();
        }
        if (AlertConstants.OBJECT_TYPE_SYNC_JOB.equalsIgnoreCase(objectType)) {
            return syncJobMapper.selectList(new QueryWrapper<SyncJob>().select("id", "name"))
                    .stream().map(s -> new AlertObjectOptionDTO(s.getId(), s.getName())).toList();
        }
        if (AlertConstants.OBJECT_TYPE_COLLECT_TASK.equalsIgnoreCase(objectType)) {
            return collectTaskMapper.selectList(new QueryWrapper<CollectTask>().select("id", "name"))
                    .stream().map(c -> new AlertObjectOptionDTO(c.getId(), c.getName())).toList();
        }
        throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "对象类型非法: " + objectType);
    }

    /**
     * 业务模块快捷入口：按对象读取告警规则（无规则返回 null）。
     */
    public AlertRuleDTO getRuleByObject(String objectType, Long objectId) {
        AlertRule rule = resolveRule(objectType, objectId);
        return rule == null ? null : getRule(rule.getId());
    }

    /**
     * 业务模块快捷入口：按对象新增或更新告警规则。
     */
    @Transactional
    public AlertRuleDTO upsertRuleByObject(String objectType, Long objectId, AlertRuleDTO dto) {
        AlertRule existing = resolveRule(objectType, objectId);
        dto.setObjectType(objectType);
        dto.setObjectId(objectId);
        if (existing == null) {
            return createRule(dto);
        }
        return updateRule(existing.getId(), dto);
    }

    // ==================== 告警历史 ====================

    public PageResult<AlertHistory> listHistory(int page, int pageSize, String objectType,
                                                Long objectId, String alertType, String sendStatus) {
        IPage<AlertHistory> p = alertHistoryMapper.selectHistoryPage(new Page<>(page, pageSize),
                objectType, objectId, alertType, sendStatus);
        return new PageResult<>(p.getRecords(), p.getTotal(), page, pageSize);
    }

    // ==================== 供触发侧使用 ====================

    /**
     * 按对象解析告警规则（同一对象最多一条，uk_alert_rule_object 保证）。
     */
    public AlertRule resolveRule(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return null;
        }
        return alertRuleMapper.selectOne(new QueryWrapper<AlertRule>()
                .eq("object_type", objectType.toUpperCase())
                .eq("object_id", objectId));
    }

    public boolean isEnabled(AlertRule rule) {
        return rule != null && rule.getEnabled() != null && rule.getEnabled() == 1;
    }

    public boolean containsTrigger(AlertRule rule, String alertType) {
        if (rule == null || !StringUtils.hasText(rule.getTriggerConditions())) {
            return false;
        }
        try {
            List<String> conditions = JSON.parseArray(rule.getTriggerConditions(), String.class);
            return conditions != null && conditions.contains(alertType);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== private ====================

    private Map<Long, List<Long>> loadUserIdsByRuleIds(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<Long>> map = new HashMap<>();
        for (AlertRuleUser aru : alertRuleUserMapper.selectByRuleIds(ruleIds)) {
            map.computeIfAbsent(aru.getAlertRuleId(), k -> new ArrayList<>()).add(aru.getUserId());
        }
        return map;
    }

    private void validate(AlertRuleDTO dto) {
        String objectType = dto.getObjectType() == null ? null : dto.getObjectType().toUpperCase();
        if (!AlertConstants.OBJECT_TYPE_DAG.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(objectType)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "对象类型非法: " + dto.getObjectType());
        }
        if (dto.getObjectId() == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "告警对象 ID 不能为空");
        }
        if (dto.getTriggerConditions() == null || dto.getTriggerConditions().isEmpty()) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "至少选择一个触发条件");
        }
        for (String trigger : dto.getTriggerConditions()) {
            if (!SUPPORTED_TRIGGERS.contains(trigger)) {
                throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "非法触发条件: " + trigger);
            }
        }
        if (dto.getTriggerConditions().contains(AlertConstants.ALERT_TIMEOUT)
                && (dto.getTimeoutMinutes() == null || dto.getTimeoutMinutes() <= 0)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "勾选超时告警时必须配置超时阈值");
        }
        if (dto.getUserIds() == null || dto.getUserIds().isEmpty()) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "必须选择至少一个接收用户");
        }
    }

    private void applyFields(AlertRule rule, AlertRuleDTO dto) {
        if (dto.getObjectType() != null) {
            rule.setObjectType(dto.getObjectType().toUpperCase());
        }
        if (dto.getObjectId() != null) {
            rule.setObjectId(dto.getObjectId());
        }
        if (StringUtils.hasText(dto.getObjectName())) {
            rule.setObjectName(dto.getObjectName());
        }
        if (dto.getTriggerConditions() != null) {
            rule.setTriggerConditions(JSON.toJSONString(dto.getTriggerConditions()));
        }
        if (dto.getTimeoutMinutes() != null) {
            rule.setTimeoutMinutes(dto.getTimeoutMinutes());
        } else if (rule.getTimeoutMinutes() == null) {
            rule.setTimeoutMinutes(30);
        }
        if (dto.getEnabled() != null) {
            rule.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        } else if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
    }

    /**
     * 服务端解析对象名，保证与对象表一致（避免前端传参不一致）。
     */
    private String resolveObjectName(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return null;
        }
        String type = objectType.toUpperCase();
        if (AlertConstants.OBJECT_TYPE_DAG.equals(type)) {
            Dag dag = dagMapper.selectById(objectId);
            return dag == null ? null : dag.getName();
        }
        if (AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(type)) {
            SyncJob job = syncJobMapper.selectById(objectId);
            return job == null ? null : job.getName();
        }
        if (AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(type)) {
            CollectTask task = collectTaskMapper.selectById(objectId);
            return task == null ? null : task.getName();
        }
        return null;
    }

    private AlertRuleDTO toDTO(AlertRule rule) {
        AlertRuleDTO dto = new AlertRuleDTO();
        dto.setId(rule.getId());
        dto.setObjectType(rule.getObjectType());
        dto.setObjectId(rule.getObjectId());
        dto.setObjectName(rule.getObjectName());
        dto.setTriggerConditions(parseConditions(rule.getTriggerConditions()));
        dto.setTimeoutMinutes(rule.getTimeoutMinutes());
        dto.setEnabled(rule.getEnabled() != null && rule.getEnabled() == 1);
        dto.setCreatedAt(rule.getCreatedAt());
        dto.setUpdatedAt(rule.getUpdatedAt());
        return dto;
    }

    private List<String> parseConditions(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
