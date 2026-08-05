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
 * Sprint 5 补充：一条规则支持绑定多个对象（alert_rule_object 关联表）。
 * 本服务不依赖 MailService，便于 system-service 通过 @Import 引入而无需邮件依赖；
 * 实际发邮件逻辑在 {@link AlertFiringService}。
 */
@Service
public class AlertRuleService {

    private static final Set<String> SUPPORTED_TRIGGERS =
            Set.of(AlertConstants.ALERT_FAILURE, AlertConstants.ALERT_TIMEOUT, AlertConstants.ALERT_SUCCESS);

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRuleUserMapper alertRuleUserMapper;
    private final AlertRuleObjectMapper alertRuleObjectMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final DagMapper dagMapper;
    private final DagProjectMapper dagProjectMapper;
    private final SyncJobMapper syncJobMapper;
    private final CollectTaskMapper collectTaskMapper;
    private final QualityJobMapper qualityJobMapper;
    private final SysUserService sysUserService;

    public AlertRuleService(AlertRuleMapper alertRuleMapper,
                            AlertRuleUserMapper alertRuleUserMapper,
                            AlertRuleObjectMapper alertRuleObjectMapper,
                            AlertHistoryMapper alertHistoryMapper,
                            DagMapper dagMapper,
                            DagProjectMapper dagProjectMapper,
                            SyncJobMapper syncJobMapper,
                            CollectTaskMapper collectTaskMapper,
                            QualityJobMapper qualityJobMapper,
                            SysUserService sysUserService) {
        this.alertRuleMapper = alertRuleMapper;
        this.alertRuleUserMapper = alertRuleUserMapper;
        this.alertRuleObjectMapper = alertRuleObjectMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.dagMapper = dagMapper;
        this.dagProjectMapper = dagProjectMapper;
        this.syncJobMapper = syncJobMapper;
        this.collectTaskMapper = collectTaskMapper;
        this.qualityJobMapper = qualityJobMapper;
        this.sysUserService = sysUserService;
    }

    // ==================== CRUD ====================

    public AlertRuleDTO getRule(Long id) {
        AlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.ALERT_RULE_NOT_FOUND, "告警规则不存在: " + id);
        }
        AlertRuleDTO dto = toDTO(rule);
        dto.setUserIds(alertRuleUserMapper.selectUserIdsByRuleId(id));
        dto.setObjectIds(loadObjectIdsByRuleId(id));
        applyUsernameNames(List.of(rule), List.of(dto));
        return dto;
    }

    public PageResult<AlertRuleDTO> listRules(int page, int pageSize, String objectType, String keyword) {
        IPage<AlertRule> p = alertRuleMapper.selectRulePage(new Page<>(page, pageSize), objectType, keyword);
        List<AlertRule> records = p.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), p.getTotal(), page, pageSize);
        }
        List<Long> ruleIds = records.stream().map(AlertRule::getId).toList();
        Map<Long, List<Long>> usersByRule = loadUserIdsByRuleIds(ruleIds);
        Map<Long, List<Long>> objectsByRule = loadObjectIdsByRuleIds(ruleIds);
        List<AlertRuleDTO> dtos = records.stream()
                .map(rule -> {
                    AlertRuleDTO dto = toDTO(rule);
                    dto.setUserIds(usersByRule.getOrDefault(rule.getId(), Collections.emptyList()));
                    dto.setObjectIds(objectsByRule.getOrDefault(rule.getId(), Collections.emptyList()));
                    return dto;
                })
                .toList();
        applyUsernameNames(records, dtos);
        return new PageResult<>(dtos, p.getTotal(), page, pageSize);
    }

    @Transactional
    public AlertRuleDTO createRule(AlertRuleDTO dto) {
        validate(dto);
        LocalDateTime now = LocalDateTime.now();
        AlertRule rule = new AlertRule();
        applyFields(rule, dto);
        rule.setName(dto.getName());
        rule.setObjectName(resolveObjectNames(dto.getObjectType(), dto.getObjectIds()));
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        alertRuleMapper.insert(rule);
        setRuleUsers(rule.getId(), dto.getUserIds());
        saveRuleObjects(rule.getId(), dto.getObjectType(), dto.getObjectIds());
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
        if (StringUtils.hasText(dto.getName())) {
            assertNameUnique(dto.getObjectType() != null ? dto.getObjectType() : rule.getObjectType(),
                    dto.getName(), id);
        }
        applyFields(rule, dto);
        if (StringUtils.hasText(dto.getObjectType()) && dto.getObjectIds() != null && !dto.getObjectIds().isEmpty()) {
            rule.setObjectName(resolveObjectNames(dto.getObjectType(), dto.getObjectIds()));
        }
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        if (dto.getUserIds() != null) {
            setRuleUsers(id, dto.getUserIds());
        }
        if (dto.getObjectIds() != null && !dto.getObjectIds().isEmpty()) {
            saveRuleObjects(id, dto.getObjectType(), dto.getObjectIds());
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
        alertRuleObjectMapper.deleteByRuleId(id);
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
     * DAG 类型按「项目 → DAG」树形返回；其他类型平铺返回。
     */
    public List<AlertObjectOptionDTO> listObjectOptions(String objectType) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        if (AlertConstants.OBJECT_TYPE_DAG.equals(type)) {
            List<DagProject> projects = dagProjectMapper.selectList(null);
            List<Dag> dags = dagMapper.selectList(null);
            Map<Long, List<Dag>> dagByProject = new HashMap<>();
            for (Dag dag : dags) {
                dagByProject.computeIfAbsent(dag.getProjectId(), k -> new ArrayList<>()).add(dag);
            }
            List<AlertObjectOptionDTO> tree = new ArrayList<>();
            for (DagProject project : projects) {
                List<AlertObjectOptionDTO> children = dagByProject.getOrDefault(project.getId(), Collections.emptyList())
                        .stream()
                        .map(d -> new AlertObjectOptionDTO(d.getId(), d.getName()))
                        .toList();
                tree.add(new AlertObjectOptionDTO(project.getId(), project.getName(), children));
            }
            return tree;
        }
        if (AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(type)) {
            return syncJobMapper.selectList(new QueryWrapper<SyncJob>().select("id", "name"))
                    .stream().map(s -> new AlertObjectOptionDTO(s.getId(), s.getName())).toList();
        }
        if (AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(type)) {
            return collectTaskMapper.selectList(new QueryWrapper<CollectTask>().select("id", "name"))
                    .stream().map(c -> new AlertObjectOptionDTO(c.getId(), c.getName())).toList();
        }
        if (AlertConstants.OBJECT_TYPE_QUALITY.equals(type)) {
            return qualityJobMapper.selectList(new QueryWrapper<QualityJob>().select("id", "name"))
                    .stream().map(q -> new AlertObjectOptionDTO(q.getId(), q.getName())).toList();
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
        dto.setObjectIds(List.of(objectId));
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
     * 按对象解析告警规则（多对象时返回包含该对象的规则）。
     */
    public AlertRule resolveRule(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return null;
        }
        List<AlertRuleObject> refs = alertRuleObjectMapper.selectByObject(objectType.toUpperCase(), objectId);
        if (refs.isEmpty()) {
            return null;
        }
        return alertRuleMapper.selectById(refs.get(0).getAlertRuleId());
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

    /**
     * 按对象删除告警规则（删除 DAG/同步任务/采集任务时级联清理）。
     * 仅移除该对象关联；若规则无其他对象则删除整条规则。
     */
    @Transactional
    public void deleteByObject(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return;
        }
        String type = objectType.toUpperCase();
        List<AlertRuleObject> refs = alertRuleObjectMapper.selectByObject(type, objectId);
        for (AlertRuleObject ref : refs) {
            Long ruleId = ref.getAlertRuleId();
            alertRuleObjectMapper.deleteById(ref.getId());
            List<AlertRuleObject> remaining = alertRuleObjectMapper.selectByRuleId(ruleId);
            if (remaining.isEmpty()) {
                alertRuleMapper.deleteById(ruleId);
                alertRuleUserMapper.deleteByRuleId(ruleId);
            }
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

    private List<Long> loadObjectIdsByRuleId(Long ruleId) {
        if (ruleId == null) {
            return Collections.emptyList();
        }
        return alertRuleObjectMapper.selectByRuleId(ruleId).stream()
                .map(AlertRuleObject::getObjectId)
                .toList();
    }

    private Map<Long, List<Long>> loadObjectIdsByRuleIds(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<Long>> map = new HashMap<>();
        // 批量查询避免 N+1
        QueryWrapper<AlertRuleObject> wrapper = new QueryWrapper<>();
        wrapper.in("alert_rule_id", ruleIds);
        for (AlertRuleObject aro : alertRuleObjectMapper.selectList(wrapper)) {
            map.computeIfAbsent(aro.getAlertRuleId(), k -> new ArrayList<>()).add(aro.getObjectId());
        }
        return map;
    }

    private void saveRuleObjects(Long ruleId, String objectType, List<Long> objectIds) {
        alertRuleObjectMapper.deleteByRuleId(ruleId);
        if (objectIds == null || objectIds.isEmpty()) {
            return;
        }
        String type = objectType == null ? "" : objectType.toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        List<AlertRuleObject> list = objectIds.stream().filter(Objects::nonNull).distinct().map(oid -> {
            AlertRuleObject aro = new AlertRuleObject();
            aro.setId(IdWorker.getId());
            aro.setAlertRuleId(ruleId);
            aro.setObjectType(type);
            aro.setObjectId(oid);
            aro.setObjectName(resolveObjectName(type, oid));
            aro.setCreatedAt(now);
            return aro;
        }).toList();
        alertRuleObjectMapper.insertBatch(list);
    }

    private void validate(AlertRuleDTO dto) {
        if (!StringUtils.hasText(dto.getName())) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "必须填写规则名称");
        }
        String objectType = dto.getObjectType() == null ? null : dto.getObjectType().toUpperCase();
        if (!AlertConstants.OBJECT_TYPE_DAG.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_QUALITY.equals(objectType)) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "对象类型非法: " + dto.getObjectType());
        }
        if (dto.getObjectIds() == null || dto.getObjectIds().isEmpty()) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID, "至少选择一个告警对象");
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
        assertNameUnique(objectType, dto.getName(), null);
    }

    /**
     * 校验规则名称在同一对象类型下唯一（创建时 excludeRuleId 传 null；更新时传自身 id 排除）。
     */
    private void assertNameUnique(String objectType, String name, Long excludeRuleId) {
        if (objectType == null || !StringUtils.hasText(name)) {
            return;
        }
        QueryWrapper<AlertRule> wrapper = new QueryWrapper<>();
        wrapper.eq("object_type", objectType.toUpperCase())
                .eq("name", name.trim());
        if (excludeRuleId != null) {
            wrapper.ne("id", excludeRuleId);
        }
        if (alertRuleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID,
                    "同一对象类型下已存在同名告警规则: " + name.trim());
        }
    }

    private void applyFields(AlertRule rule, AlertRuleDTO dto) {
        if (StringUtils.hasText(dto.getName())) {
            rule.setName(dto.getName().trim());
        }
        if (dto.getObjectType() != null) {
            rule.setObjectType(dto.getObjectType().toUpperCase());
        }
        if (dto.getEnabled() != null) {
            rule.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        } else if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
        if (dto.getTriggerConditions() != null) {
            rule.setTriggerConditions(JSON.toJSONString(dto.getTriggerConditions()));
        }
        if (dto.getTimeoutMinutes() != null) {
            rule.setTimeoutMinutes(dto.getTimeoutMinutes());
        } else if (rule.getTimeoutMinutes() == null) {
            rule.setTimeoutMinutes(30);
        }
    }

    private String resolveObjectNames(String objectType, List<Long> objectIds) {
        if (objectType == null || objectIds == null || objectIds.isEmpty()) {
            return null;
        }
        String type = objectType.toUpperCase();
        List<String> names = objectIds.stream()
                .map(oid -> resolveObjectName(type, oid))
                .filter(StringUtils::hasText)
                .toList();
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 按对象类型和 ID 解析对象名称（供触发侧构建邮件主题/正文使用）。
     */
    public String resolveObjectName(String objectType, Long objectId) {
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
        if (AlertConstants.OBJECT_TYPE_QUALITY.equals(type)) {
            QualityJob job = qualityJobMapper.selectById(objectId);
            return job == null ? null : job.getName();
        }
        return null;
    }

    /**
     * 批量回填规则的创建人/修改人用户名（避免 N+1：一次查全部 userId → username）。
     */
    private void applyUsernameNames(List<AlertRule> records, List<AlertRuleDTO> dtos) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (AlertRule rule : records) {
            if (rule.getCreatedBy() != null) {
                ids.add(rule.getCreatedBy());
            }
            if (rule.getUpdatedBy() != null) {
                ids.add(rule.getUpdatedBy());
            }
        }
        Map<Long, String> usernameMap = sysUserService.getUsernameMap(ids);
        for (int i = 0; i < records.size(); i++) {
            AlertRuleDTO dto = dtos.get(i);
            dto.setCreatedByName(usernameMap.get(records.get(i).getCreatedBy()));
            dto.setUpdatedByName(usernameMap.get(records.get(i).getUpdatedBy()));
        }
    }

    private AlertRuleDTO toDTO(AlertRule rule) {
        AlertRuleDTO dto = new AlertRuleDTO();
        dto.setId(rule.getId());
        dto.setName(rule.getName());
        dto.setObjectType(rule.getObjectType());
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
