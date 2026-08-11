package com.datanest.alert.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.constant.AlertConstants;
import com.datanest.alert.dto.AlertObjectOptionDTO;
import com.datanest.alert.dto.AlertRuleDTO;
import com.datanest.alert.entity.*;
import com.datanest.alert.mapper.*;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringObjectApi;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.realtime.api.CdcPipelineApi;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 通用告警规则服务（CRUD + 历史 + 对象选择）。
 * Sprint 5 决策：收件人改为平台用户（alert_rule_user 存 user_id，发送时反查 sys_user.email）。
 * Sprint 5 补充：一条规则支持绑定多个对象（alert_rule_object 关联表）。
 * 微服务化改造：跨域读取（DAG/同步任务/采集任务/质量任务/用户）全部改走 Feign 内部接口
 * （engineering / governance / system），失败时降级返回空数据并记 warn，不阻断规则保存；
 * 实际发邮件逻辑在 {@link AlertFiringService}。
 */
@Service
public class AlertRuleService {

    private static final Logger logger = LoggerFactory.getLogger(AlertRuleService.class);

    private static final Set<String> SUPPORTED_TRIGGERS =
            Set.of(AlertConstants.ALERT_FAILURE, AlertConstants.ALERT_TIMEOUT, AlertConstants.ALERT_SUCCESS,
                    // Sprint 9 F3：CDC 管道触发条件（延迟超阈值 / 外部停止）
                    AlertConstants.ALERT_LAG_EXCEEDED, AlertConstants.ALERT_EXTERNAL_STOP);

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRuleUserMapper alertRuleUserMapper;
    private final AlertRuleObjectMapper alertRuleObjectMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final EngineeringObjectApi engineeringObjectApi;
    private final GovernanceObjectApi governanceObjectApi;
    private final SystemUserApi systemUserApi;
    /** Sprint 9 F3：CDC 管道对象名反查/可选对象下拉（realtime 内部端点） */
    private final CdcPipelineApi cdcPipelineApi;

    public AlertRuleService(AlertRuleMapper alertRuleMapper,
                            AlertRuleUserMapper alertRuleUserMapper,
                            AlertRuleObjectMapper alertRuleObjectMapper,
                            AlertHistoryMapper alertHistoryMapper,
                            EngineeringObjectApi engineeringObjectApi,
                            GovernanceObjectApi governanceObjectApi,
                            SystemUserApi systemUserApi,
                            CdcPipelineApi cdcPipelineApi) {
        this.alertRuleMapper = alertRuleMapper;
        this.alertRuleUserMapper = alertRuleUserMapper;
        this.alertRuleObjectMapper = alertRuleObjectMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.engineeringObjectApi = engineeringObjectApi;
        this.governanceObjectApi = governanceObjectApi;
        this.systemUserApi = systemUserApi;
        this.cdcPipelineApi = cdcPipelineApi;
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
        rule.setObjectName(resolveObjectNamesForSave(dto.getObjectType(), dto.getObjectIds()));
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
        // dto 未带 objectType 时沿用规则原类型（名称解析与 alert_rule_object 写入都用生效类型）
        String effectiveType = StringUtils.hasText(dto.getObjectType()) ? dto.getObjectType() : rule.getObjectType();
        if (dto.getObjectIds() != null && !dto.getObjectIds().isEmpty()) {
            // 保存路径 fail-closed：objectIds 非空即会写 alert_rule_object，
            // 名称解析失败（远端不可用或对象不存在）必须拒绝保存而非持久化空名称
            String objectName = resolveObjectNamesForSave(effectiveType, dto.getObjectIds());
            if (StringUtils.hasText(dto.getObjectType())) {
                rule.setObjectName(objectName);
            }
        }
        rule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(rule);
        if (dto.getUserIds() != null) {
            setRuleUsers(id, dto.getUserIds());
        }
        if (dto.getObjectIds() != null && !dto.getObjectIds().isEmpty()) {
            saveRuleObjects(id, effectiveType, dto.getObjectIds());
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
     * DAG 类型按「项目 → DAG」树形返回（engineering 内部接口）；其他类型平铺返回。
     * 远端查询失败时降级返回空列表并记 warn（下拉为空，不阻断页面）。
     */
    public List<AlertObjectOptionDTO> listObjectOptions(String objectType) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        if (AlertConstants.OBJECT_TYPE_DAG.equals(type) || AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(type)) {
            return RemoteCalls.execute("engineering.options", () -> {
                Result<List<com.datanest.engineering.api.dto.ObjectOptionDTO>> result =
                        engineeringObjectApi.options(type);
                List<com.datanest.engineering.api.dto.ObjectOptionDTO> options =
                        result == null || result.data() == null ? Collections.emptyList() : result.data();
                return options.stream().map(this::toOption).toList();
            }, Collections.emptyList());
        }
        if (AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(type) || AlertConstants.OBJECT_TYPE_QUALITY.equals(type)) {
            return RemoteCalls.execute("governance.options", () -> {
                Result<List<com.datanest.governance.api.dto.ObjectOptionDTO>> result =
                        governanceObjectApi.options(type);
                List<com.datanest.governance.api.dto.ObjectOptionDTO> options =
                        result == null || result.data() == null ? Collections.emptyList() : result.data();
                return options.stream().map(this::toOption).toList();
            }, Collections.emptyList());
        }
        if (AlertConstants.OBJECT_TYPE_CDC_PIPELINE.equals(type)) {
            // Sprint 9 F3：CDC 管道对象下拉走 realtime 内部端点（ids 为空返回全部管道；fail-open 降级空列表）
            return RemoteCalls.execute("realtime.cdc.options", () -> {
                Result<Map<Long, String>> result = cdcPipelineApi.names(null);
                Map<Long, String> data = result == null || result.data() == null
                        ? Collections.emptyMap() : result.data();
                return data.entrySet().stream()
                        .map(e -> new AlertObjectOptionDTO(e.getKey(), e.getValue(), null))
                        .toList();
            }, Collections.emptyList());
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
        applyHistoryObjectNames(p.getRecords());
        return new PageResult<>(p.getRecords(), p.getTotal(), page, pageSize);
    }

    /**
     * 回填告警历史的 objectName（微服务化 5.0：替代原跨域 JOIN）。
     * 按 objectType 分组，每种类型一次批量 Feign 调用（DAG/SYNC_JOB → engineering，
     * COLLECT_TASK/QUALITY → governance），避免 N+1；
     * 远端不可用时经 RemoteCalls 降级为空 Map（objectName 列为 null），列表接口正常返回。
     */
    private void applyHistoryObjectNames(List<AlertHistory> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, List<Long>> idsByType = new HashMap<>();
        for (AlertHistory record : records) {
            if (record.getObjectType() != null && record.getObjectId() != null) {
                idsByType.computeIfAbsent(record.getObjectType(), k -> new ArrayList<>())
                        .add(record.getObjectId());
            }
        }
        Map<String, Map<Long, String>> namesByType = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : idsByType.entrySet()) {
            namesByType.put(entry.getKey(), fetchObjectNames(entry.getKey(), entry.getValue()));
        }
        for (AlertHistory record : records) {
            Map<Long, String> names = namesByType.get(record.getObjectType());
            if (names != null) {
                record.setObjectName(names.get(record.getObjectId()));
            }
        }
    }

    /**
     * 按质量检查批次 ID 查询告警历史（内部接口，供治理服务批次详情反查）。
     */
    public List<AlertHistory> listHistoryByQualityBatch(Long batchId) {
        if (batchId == null) {
            return Collections.emptyList();
        }
        return alertHistoryMapper.selectByQualityBatchId(batchId);
    }

    /**
     * 清理发送时间早于 now - beforeDays 的告警历史，返回删除条数。
     */
    @Transactional
    public int cleanupHistory(int beforeDays) {
        if (beforeDays <= 0) {
            return 0;
        }
        return alertHistoryMapper.deleteSentBefore(LocalDateTime.now().minusDays(beforeDays));
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
     * 按对象查询引用它的告警规则名称列表（消费方删除对象前的引用校验用）。
     */
    public List<String> listRuleNamesByObject(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return List.of();
        }
        List<AlertRuleObject> refs = alertRuleObjectMapper.selectByObject(objectType.toUpperCase(), objectId);
        if (refs.isEmpty()) {
            return List.of();
        }
        return alertRuleMapper.selectBatchIds(refs.stream().map(AlertRuleObject::getAlertRuleId).toList())
                .stream().map(AlertRule::getName)
                .toList();
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
        // 批量解析对象名（一次 Feign 请求多 id），保存路径 fail-closed：
        // 解析结果为空说明远端不可用或对象不存在（此处不区分，两者落库都是错误状态），
        // 抛错回滚而非写出空 object_name
        List<Long> distinctIds = objectIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nameMap = fetchObjectNames(type, distinctIds);
        if (!distinctIds.isEmpty() && nameMap.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "对象服务不可用或对象不存在，请稍后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        List<AlertRuleObject> list = distinctIds.stream().map(oid -> {
            AlertRuleObject aro = new AlertRuleObject();
            aro.setId(IdWorker.getId());
            aro.setAlertRuleId(ruleId);
            aro.setObjectType(type);
            aro.setObjectId(oid);
            aro.setObjectName(nameMap.get(oid));
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
                && !AlertConstants.OBJECT_TYPE_QUALITY.equals(objectType)
                && !AlertConstants.OBJECT_TYPE_CDC_PIPELINE.equals(objectType)) {
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
        // Sprint 9 F3：延迟超阈值/外部停止仅适用 CDC 管道对象（防其它对象配无意义组合）
        if (!AlertConstants.OBJECT_TYPE_CDC_PIPELINE.equals(objectType)
                && (dto.getTriggerConditions().contains(AlertConstants.ALERT_LAG_EXCEEDED)
                || dto.getTriggerConditions().contains(AlertConstants.ALERT_EXTERNAL_STOP))) {
            throw new BusinessException(ErrorCode.ALERT_RULE_OBJECT_INVALID,
                    "触发条件「延迟超阈值/外部停止」仅适用于 CDC 管道对象");
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
        Map<Long, String> nameMap = fetchObjectNames(type, objectIds);
        List<String> names = objectIds.stream()
                .map(nameMap::get)
                .filter(StringUtils::hasText)
                .toList();
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 保存路径（createRule/updateRule）专用的对象名解析：fail-closed。
     * objectIds 非空但解析结果为空时抛错拒绝保存（事务回滚），
     * 防止远端降级空 Map 导致空 object_name 持久化（落库后不会自动修复）。
     * 不区分「远端失败」与「对象真的不存在」——两种情况下保存都是错误状态，共用同一报错。
     * fire/展示路径仍走 {@link #resolveObjectNames} 的降级语义，不受影响。
     */
    private String resolveObjectNamesForSave(String objectType, List<Long> objectIds) {
        String objectName = resolveObjectNames(objectType, objectIds);
        if (objectName == null && objectIds != null && !objectIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "对象服务不可用或对象不存在，请稍后重试");
        }
        return objectName;
    }

    /**
     * 按对象类型和 ID 解析对象名称（供触发侧构建邮件主题/正文使用）。
     * 名称解析失败返回 null 并记 warn，不阻断告警发送。
     */
    public String resolveObjectName(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return null;
        }
        return fetchObjectNames(objectType.toUpperCase(), List.of(objectId)).get(objectId);
    }

    /**
     * 批量解析对象名称：DAG / SYNC_JOB 走 engineering，COLLECT_TASK / QUALITY 走 governance。
     * 远端调用失败时降级返回空 map 并记 warn（名称解析失败不应阻断规则保存/告警发送）。
     */
    private Map<Long, String> fetchObjectNames(String objectType, List<Long> objectIds) {
        if (objectType == null || objectIds == null || objectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = objectIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常，warn + 计数后返回空 map
        return RemoteCalls.execute("alert.object-names", () -> {
            if (AlertConstants.OBJECT_TYPE_DAG.equals(objectType)
                    || AlertConstants.OBJECT_TYPE_SYNC_JOB.equals(objectType)) {
                com.datanest.engineering.api.dto.ObjectNameRequest request =
                        new com.datanest.engineering.api.dto.ObjectNameRequest();
                request.setObjectType(objectType);
                request.setIds(ids);
                Result<Map<Long, String>> result = engineeringObjectApi.names(request);
                return result != null && result.data() != null ? result.data() : Collections.<Long, String>emptyMap();
            }
            if (AlertConstants.OBJECT_TYPE_COLLECT_TASK.equals(objectType)
                    || AlertConstants.OBJECT_TYPE_QUALITY.equals(objectType)) {
                com.datanest.governance.api.dto.ObjectNameRequest request =
                        new com.datanest.governance.api.dto.ObjectNameRequest();
                request.setObjectType(objectType);
                request.setIds(ids);
                Result<Map<Long, String>> result = governanceObjectApi.names(request);
                return result != null && result.data() != null ? result.data() : Collections.<Long, String>emptyMap();
            }
            if (AlertConstants.OBJECT_TYPE_CDC_PIPELINE.equals(objectType)) {
                // Sprint 9 F3：CDC 管道对象名反查走 realtime（fail-open：降级空 Map 不阻断）
                Result<Map<Long, String>> result = cdcPipelineApi.names(ids);
                return result != null && result.data() != null ? result.data() : Collections.<Long, String>emptyMap();
            }
            return Collections.<Long, String>emptyMap();
        }, Collections.emptyMap());
    }

    /**
     * 批量回填规则的创建人/修改人用户名（一次 Feign 请求全部 userId → username）。
     * 远端查询失败时降级不回填并记 warn，不影响列表返回。
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
        Map<Long, String> usernameMap = Collections.emptyMap();
        List<Long> validIds = ids.stream().filter(id -> id > 0).distinct().toList();
        if (!validIds.isEmpty()) {
            // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常，warn + 计数后不回填用户名
            usernameMap = RemoteCalls.execute("system.usernames", () -> {
                Result<Map<Long, String>> result = systemUserApi.usernames(validIds);
                return result != null && result.data() != null ? result.data() : Collections.<Long, String>emptyMap();
            }, Collections.emptyMap());
        }
        for (int i = 0; i < records.size(); i++) {
            AlertRuleDTO dto = dtos.get(i);
            dto.setCreatedByName(usernameMap.get(records.get(i).getCreatedBy()));
            dto.setUpdatedByName(usernameMap.get(records.get(i).getUpdatedBy()));
        }
    }

    private AlertObjectOptionDTO toOption(com.datanest.engineering.api.dto.ObjectOptionDTO option) {
        List<AlertObjectOptionDTO> children = option.getChildren() == null ? null
                : option.getChildren().stream().map(this::toOption).toList();
        return new AlertObjectOptionDTO(option.getId(), option.getName(), children);
    }

    private AlertObjectOptionDTO toOption(com.datanest.governance.api.dto.ObjectOptionDTO option) {
        List<AlertObjectOptionDTO> children = option.getChildren() == null ? null
                : option.getChildren().stream().map(this::toOption).toList();
        return new AlertObjectOptionDTO(option.getId(), option.getName(), children);
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
