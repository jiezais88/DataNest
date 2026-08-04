package com.datanest.task.core.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.dto.QualityRuleBatchCreateRequest;
import com.datanest.task.core.dto.QualityRuleCreateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.task.core.dto.QualityRuleUpdateRequest;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityRuleTemplate;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityRuleTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量规则服务（Sprint 6 配置层）。
 * <p>
 * 规则 CRUD + 模板批量应用（选模板 + 多表，逐表可微调）+ 启停 + 单条执行预留。
 * {@code sql_expression} 执行时动态生成（{@link RuleSqlGenerator}），配置层不落库。
 */
@Service
public class QualityRuleService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "COMPLETENESS", "UNIQUENESS", "RANGE", "CUSTOM_SQL"
    );

    private final QualityRuleMapper ruleMapper;
    private final QualityJobMapper jobMapper;
    private final QualityRuleTemplateMapper templateMapper;
    private final MetadataTableMapper tableMapper;
    private final SysUserService sysUserService;

    public QualityRuleService(QualityRuleMapper ruleMapper,
                              QualityJobMapper jobMapper,
                              QualityRuleTemplateMapper templateMapper,
                              MetadataTableMapper tableMapper,
                              SysUserService sysUserService) {
        this.ruleMapper = ruleMapper;
        this.jobMapper = jobMapper;
        this.templateMapper = templateMapper;
        this.tableMapper = tableMapper;
        this.sysUserService = sysUserService;
    }

    // ==================== 查询 ====================

    /**
     * 按任务查规则列表（含冗余回填表名/模板名/数据源名，避免 N+1）。
     */
    public List<QualityRuleDTO> listByJob(Long jobId) {
        List<QualityRule> records = ruleMapper.selectList(
                new QueryWrapper<QualityRule>().eq("job_id", jobId).orderByAsc("id"));
        return buildDTOs(records);
    }

    public QualityRuleDTO getById(Long id) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        return buildDTOs(List.of(entity)).get(0);
    }

    // ==================== 写操作 ====================

    /**
     * 任务下新增规则。
     */
    @Transactional
    public QualityRuleDTO create(QualityRuleCreateRequest request) {
        Long jobId = request.getJobId();
        requireJob(jobId);
        validateType(request.getType());
        MetadataTable table = requireTable(request.getTableId());
        QualityRuleTemplate template = request.getTemplateId() == null
                ? null : templateMapper.selectById(request.getTemplateId());

        String name = request.getName().trim();
        if (countByJobAndName(jobId, name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "任务下已存在同名规则: " + name);
        }

        QualityRule entity = new QualityRule();
        entity.setJobId(jobId);
        entity.setTemplateId(request.getTemplateId());
        entity.setName(name);
        entity.setType(request.getType().trim().toUpperCase());
        entity.setTableId(request.getTableId());
        entity.setColumnName(request.getColumnName());
        entity.setCheckField(request.getCheckField() == null ? 0 : request.getCheckField());
        // 模板类规则执行时动态生成 SQL，落库不存；CUSTOM_SQL 直接存用户 SQL
        entity.setSqlExpression("CUSTOM_SQL".equals(request.getType()) ? request.getSqlExpression() : null);
        entity.setWarningThreshold(request.getWarningThreshold());
        entity.setSevereThreshold(request.getSevereThreshold());
        // 值域边界：仅 RANGE 类型落库（DTO 已校验必填），其余类型为 null
        if ("RANGE".equals(request.getType())) {
            entity.setRangeMin(request.getRangeMin());
            entity.setRangeMax(request.getRangeMax());
        }
        // 结果指标名：优先取模板，其次取请求
        entity.setResultMetric(template != null && template.getResultMetric() != null
                ? template.getResultMetric() : request.getResultMetric());
        entity.setWeight(request.getWeight() == null ? 1 : request.getWeight());
        entity.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        ruleMapper.insert(entity);
        return getById(entity.getId());
    }

    /**
     * 模板批量应用：选「1 个模板 + 多张表」，逐表生成独立规则实例（逐表可微调）。
     */
    @Transactional
    public List<QualityRuleDTO> batchCreate(QualityRuleBatchCreateRequest request) {
        requireJob(request.getJobId());
        QualityRuleTemplate template = requireTemplate(request.getTemplateId());
        validateTemplateForRule(template);

        // 批量校验表存在，并一次性回填表对象
        Set<Long> tableIds = request.getItems().stream()
                .map(QualityRuleBatchCreateRequest.RuleItem::getTableId)
                .collect(Collectors.toSet());
        List<MetadataTable> tables = tableMapper.selectBatchIds(tableIds);
        Map<Long, MetadataTable> tableMap = tables.stream()
                .collect(Collectors.toMap(MetadataTable::getId, Function.identity()));
        if (tableMap.size() != tableIds.size()) {
            throw new BusinessException(ErrorCode.QUALITY_TABLE_NOT_FOUND, "批量应用存在不存在的目标表");
        }

        Long currentUserId = currentUserId();
        LocalDateTime now = LocalDateTime.now();
        List<QualityRule> created = new ArrayList<>();
        for (QualityRuleBatchCreateRequest.RuleItem item : request.getItems()) {
            MetadataTable table = tableMap.get(item.getTableId());
            validateItemForTemplate(template, item);

            QualityRule entity = new QualityRule();
            entity.setJobId(request.getJobId());
            entity.setTemplateId(template.getId());
            entity.setName(resolveBatchName(template, table, item, request));
            entity.setType(template.getType());
            entity.setTableId(item.getTableId());
            entity.setColumnName(item.getColumnName());
            entity.setCheckField(item.getCheckField() == null ? 0 : item.getCheckField());
            entity.setSqlExpression(RuleSqlGenerator.isCustomSql(template) ? item.getSqlExpression() : null);
            entity.setWarningThreshold(item.getWarningThreshold());
            entity.setSevereThreshold(item.getSevereThreshold());
            // 值域边界：仅 RANGE 模板落库（validateItemForTemplate 已校验必填）
            if ("RANGE".equals(template.getType())) {
                entity.setRangeMin(item.getRangeMin());
                entity.setRangeMax(item.getRangeMax());
            }
            entity.setResultMetric(template.getResultMetric());
            entity.setWeight(item.getWeight() == null ? 1 : item.getWeight());
            entity.setEnabled(1);
            entity.setCreatedBy(currentUserId);
            entity.setUpdatedBy(currentUserId);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            ruleMapper.insert(entity);
            created.add(entity);
        }
        return buildDTOs(created);
    }

    /**
     * 编辑规则。
     */
    @Transactional
    public QualityRuleDTO update(Long id, QualityRuleUpdateRequest request) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        validateType(request.getType());
        String name = request.getName().trim();
        if (!entity.getName().equals(name) && countByJobAndName(entity.getJobId(), name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "任务下已存在同名规则: " + name);
        }
        if (request.getTableId() != null) {
            requireTable(request.getTableId());
        }

        // 更新语义：全量覆盖（规则编辑表单前端总是全量提交，DTO @AssertTrue 强校验完整数据；
        // RANGE 必填 columnName/rangeMin/rangeMax，非 RANGE 清理值域）
        entity.setName(name);
        entity.setType(request.getType().trim().toUpperCase());
        if (request.getTableId() != null) {
            entity.setTableId(request.getTableId());
        }
        entity.setColumnName(request.getColumnName());
        entity.setCheckField(request.getCheckField() == null ? 0 : request.getCheckField());
        entity.setSqlExpression("CUSTOM_SQL".equals(request.getType()) ? request.getSqlExpression() : null);
        entity.setWarningThreshold(request.getWarningThreshold());
        entity.setSevereThreshold(request.getSevereThreshold());
        // 值域边界：RANGE 落库（DTO 已校验必填），非 RANGE 清空
        if ("RANGE".equals(request.getType())) {
            entity.setRangeMin(request.getRangeMin());
            entity.setRangeMax(request.getRangeMax());
        } else {
            entity.setRangeMin(null);
            entity.setRangeMax(null);
        }
        entity.setResultMetric(request.getResultMetric());
        entity.setWeight(request.getWeight() == null ? 1 : request.getWeight());
        entity.setEnabled(request.getEnabled() == null ? entity.getEnabled() : request.getEnabled());
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(entity);
        return getById(id);
    }

    /**
     * 删除规则。
     */
    @Transactional
    public void delete(Long id) {
        if (ruleMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        ruleMapper.deleteById(id);
    }

    /**
     * 启停规则。
     */
    @Transactional
    public QualityRuleDTO toggle(Long id, Boolean enabled) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        boolean target = enabled != null ? enabled : (entity.getEnabled() == null || entity.getEnabled() != 1);
        entity.setEnabled(target ? 1 : 0);
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(entity);
        return getById(id);
    }

    /**
     * 单条规则执行（预留）：执行校验下一批实现。
     */
    public void executeRule(Long id) {
        requireRule(id);
        throw new BusinessException(ErrorCode.QUALITY_RULE_EXECUTE_NOT_IMPLEMENTED, "执行功能待实现");
    }

    /**
     * 预览规则执行 SQL（供前端编辑时查看模板展开结果）。
     */
    public String previewSql(Long ruleId) {
        QualityRule entity = ruleMapper.selectById(ruleId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + ruleId);
        }
        QualityRuleTemplate template = entity.getTemplateId() == null
                ? null : templateMapper.selectById(entity.getTemplateId());
        MetadataTable table = entity.getTableId() == null ? null : tableMapper.selectById(entity.getTableId());
        // {min}/{max} 占位符来自 RANGE 值域边界 range_min/range_max（与分级阈值无关）
        return RuleSqlGenerator.generate(template, table, entity.getColumnName(),
                entity.getRangeMin(), entity.getRangeMax(), entity.getSqlExpression());
    }

    // ==================== 内部协作（供 QualityJobService 级联删除） ====================

    /**
     * 删除任务下所有规则（任务删除级联调用）。
     */
    public void deleteByJob(Long jobId) {
        ruleMapper.delete(new QueryWrapper<QualityRule>().eq("job_id", jobId));
    }

    /**
     * 统计任务下规则数（任务列表冗余回填）。
     */
    public long countByJob(Long jobId) {
        return ruleMapper.selectCount(new QueryWrapper<QualityRule>().eq("job_id", jobId));
    }

    /**
     * 批量统计多个任务下规则数（避免 N+1：一次 GROUP BY 查询全部任务）。返回 jobId → 规则数。
     */
    public Map<Long, Long> countByJobIds(List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<QualityRule> wrapper = new QueryWrapper<QualityRule>()
                .select("job_id AS job_id, COUNT(*) AS cnt")
                .in("job_id", jobIds)
                .groupBy("job_id");
        List<Map<String, Object>> rows = ruleMapper.selectMaps(wrapper);
        Map<Long, Long> map = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object jobId = row.get("job_id");
            Object cnt = row.get("cnt");
            if (jobId instanceof Number jobIdNum && cnt instanceof Number cntNum) {
                map.put(jobIdNum.longValue(), cntNum.longValue());
            }
        }
        return map;
    }

    // ==================== private ====================

    private void requireJob(Long jobId) {
        if (jobMapper.selectById(jobId) == null) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NOT_FOUND, "质量任务不存在: " + jobId);
        }
    }

    private QualityRule requireRule(Long id) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        return entity;
    }

    private QualityRuleTemplate requireTemplate(Long templateId) {
        QualityRuleTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND, "质量规则模板不存在: " + templateId);
        }
        return template;
    }

    private void validateTemplateForRule(QualityRuleTemplate template) {
        if (template.getEnabled() != null && template.getEnabled() == 0) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_TYPE_INVALID, "模板已停用，不可用于批量生成: " + template.getName());
        }
        if (!SUPPORTED_TYPES.contains(template.getType())) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_TYPE_INVALID, "模板类型非法: " + template.getType());
        }
    }

    private void validateItemForTemplate(QualityRuleTemplate template, QualityRuleBatchCreateRequest.RuleItem item) {
        String type = template.getType();
        // RANGE / UNIQUENESS / 按字段完整性 需填字段
        boolean fieldRequired = "RANGE".equals(type) || "UNIQUENESS".equals(type)
                || ("COMPLETENESS".equals(type) && item.getCheckField() != null && item.getCheckField() == 1);
        if (fieldRequired && (item.getColumnName() == null || item.getColumnName().isBlank())) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_BATCH_TEMPLATE_INVALID,
                    "模板类型 " + type + " 必须为每张表指定检查字段");
        }
        // RANGE 模板必须为每张表指定值域边界（range_min ≤ range_max）
        if ("RANGE".equals(type)) {
            if (item.getRangeMin() == null || item.getRangeMax() == null
                    || item.getRangeMin().compareTo(item.getRangeMax()) > 0) {
                throw new BusinessException(ErrorCode.QUALITY_RULE_BATCH_TEMPLATE_INVALID,
                        "模板类型 RANGE 必须为每张表指定值域边界 rangeMin/rangeMax，且 rangeMin ≤ rangeMax");
            }
        }
    }

    private String resolveBatchName(QualityRuleTemplate template, MetadataTable table,
                                    QualityRuleBatchCreateRequest.RuleItem item,
                                    QualityRuleBatchCreateRequest request) {
        // 用户指定名称，或按模板名 + 表名生成
        if (item.getName() != null && !item.getName().isBlank()) {
            return item.getName().trim();
        }
        String tableName = table == null ? "" : table.getTableName();
        return template.getName() + "·" + tableName;
    }

    private void validateType(String type) {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_TYPE_INVALID,
                    "规则类型非法: " + type + "（仅支持 " + SUPPORTED_TYPES + "）");
        }
    }

    private MetadataTable requireTable(Long tableId) {
        MetadataTable table = tableMapper.selectById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.QUALITY_TABLE_NOT_FOUND, "目标表不存在: " + tableId);
        }
        return table;
    }

    private long countByJobAndName(Long jobId, String name) {
        return ruleMapper.selectCount(new QueryWrapper<QualityRule>()
                .eq("job_id", jobId).eq("name", name));
    }

    /**
     * 批量构建 DTO，一次性回填表名/模板名/用户名，避免 N+1。
     */
    private List<QualityRuleDTO> buildDTOs(List<QualityRule> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        // 表信息
        Set<Long> tableIds = records.stream()
                .map(QualityRule::getTableId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MetadataTable> tableMap = tableIds.isEmpty()
                ? Map.of() : tableMapper.selectBatchIds(tableIds).stream()
                .collect(Collectors.toMap(MetadataTable::getId, Function.identity()));
        // 模板信息
        Set<Long> templateIds = records.stream()
                .map(QualityRule::getTemplateId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QualityRuleTemplate> templateMap = templateIds.isEmpty()
                ? Map.of() : templateMapper.selectBatchIds(templateIds).stream()
                .collect(Collectors.toMap(QualityRuleTemplate::getId, Function.identity()));
        // 用户名映射
        Map<Long, String> usernameMap = loadUsernameMap(records);

        return records.stream().map(e -> toDTO(e, tableMap, templateMap, usernameMap)).toList();
    }

    private QualityRuleDTO toDTO(QualityRule entity, Map<Long, MetadataTable> tableMap,
                                 Map<Long, QualityRuleTemplate> templateMap,
                                 Map<Long, String> usernameMap) {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setId(entity.getId());
        dto.setJobId(entity.getJobId());
        dto.setTemplateId(entity.getTemplateId());
        QualityRuleTemplate template = entity.getTemplateId() == null ? null : templateMap.get(entity.getTemplateId());
        dto.setTemplateName(template == null ? null : template.getName());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setTableId(entity.getTableId());
        MetadataTable table = entity.getTableId() == null ? null : tableMap.get(entity.getTableId());
        dto.setTableName(table == null ? null : RuleSqlGenerator.buildFullTableName(table));
        dto.setColumnName(entity.getColumnName());
        dto.setCheckField(entity.getCheckField());
        dto.setSqlExpression(entity.getSqlExpression());
        dto.setWarningThreshold(entity.getWarningThreshold());
        dto.setSevereThreshold(entity.getSevereThreshold());
        dto.setRangeMin(entity.getRangeMin());
        dto.setRangeMax(entity.getRangeMax());
        dto.setResultMetric(entity.getResultMetric());
        dto.setWeight(entity.getWeight());
        dto.setEnabled(entity.getEnabled());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setCreatedByName(entity.getCreatedBy() == null ? null : usernameMap.get(entity.getCreatedBy()));
        dto.setUpdatedByName(entity.getUpdatedBy() == null ? null : usernameMap.get(entity.getUpdatedBy()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private Map<Long, String> loadUsernameMap(List<QualityRule> records) {
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
