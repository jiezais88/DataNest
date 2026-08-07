package com.datanest.task.core.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.dto.QualityRuleBatchCreateRequest;
import com.datanest.task.core.dto.QualityRuleCreateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.task.core.dto.QualityRuleQueryRequest;
import com.datanest.task.core.dto.QualityRuleUpdateRequest;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.entity.QualityJobRule;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityRuleTemplate;
import com.datanest.task.core.entity.QualityScore;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import com.datanest.task.core.mapper.QualityJobRuleMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityRuleTemplateMapper;
import com.datanest.task.core.mapper.QualityScoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量规则服务（Sprint 7 规则独立化）。
 * <p>
 * 规则可独立创建（jobId 可空），任务通过 {@code quality_job_rule} 关联表多对多引用规则。
 * 规则 CRUD + 模板批量应用（选模板 + 多表，逐表可微调）+ 启停 + 分页查询 + 单条执行预留。
 * {@code sql_expression} 执行时动态生成（{@link RuleSqlGenerator}），配置层不落库。
 */
@Service
public class QualityRuleService {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleService.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "COMPLETENESS", "UNIQUENESS", "RANGE", "CUSTOM_SQL"
    );

    private final QualityRuleMapper ruleMapper;
    private final QualityJobMapper jobMapper;
    private final QualityJobRuleMapper jobRuleMapper;
    private final QualityRuleTemplateMapper templateMapper;
    private final MetadataTableMapper tableMapper;
    private final EngineeringDatasourceApi datasourceApi;
    private final SystemUserApi systemUserApi;
    private final QualityCheckTriggerService triggerService;
    private final QualityScoreMapper qualityScoreMapper;

    public QualityRuleService(QualityRuleMapper ruleMapper,
                              QualityJobMapper jobMapper,
                              QualityJobRuleMapper jobRuleMapper,
                              QualityRuleTemplateMapper templateMapper,
                              MetadataTableMapper tableMapper,
                              EngineeringDatasourceApi datasourceApi,
                              SystemUserApi systemUserApi,
                              QualityCheckTriggerService triggerService,
                              QualityScoreMapper qualityScoreMapper) {
        this.ruleMapper = ruleMapper;
        this.jobMapper = jobMapper;
        this.jobRuleMapper = jobRuleMapper;
        this.templateMapper = templateMapper;
        this.tableMapper = tableMapper;
        this.datasourceApi = datasourceApi;
        this.systemUserApi = systemUserApi;
        this.triggerService = triggerService;
        this.qualityScoreMapper = qualityScoreMapper;
    }

    // ==================== 查询 ====================

    /**
     * 按任务查规则列表（Sprint 7 经 quality_job_rule 关联表查询）。
     */
    public List<QualityRuleDTO> listByJob(Long jobId) {
        List<Long> ruleIds = listRuleIdsByJob(jobId);
        if (ruleIds.isEmpty()) {
            return List.of();
        }
        List<QualityRule> records = ruleMapper.selectList(
                new QueryWrapper<QualityRule>().in("id", ruleIds).orderByAsc("id"));
        return buildDTOs(records);
    }

    public QualityRuleDTO getById(Long id) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        return buildDTOs(List.of(entity)).get(0);
    }

    /**
     * 分页查询规则（Sprint 7 规则独立菜单）。
     * 支持按规则名关键字、类型、启用状态、所属任务、目标表过滤；回填所属任务名。
     */
    public PageResult<QualityRuleDTO> page(QualityRuleQueryRequest request) {
        IPage<QualityRule> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<QualityRule> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim());
        }
        if (request.getType() != null && !request.getType().isBlank()) {
            wrapper.eq("type", request.getType().trim().toUpperCase());
        }
        if (request.getEnabled() != null) {
            wrapper.eq("enabled", request.getEnabled());
        }
        if (request.getTableId() != null) {
            wrapper.eq("table_id", request.getTableId());
        }
        // 所属任务过滤：经 quality_job_rule 关联表（规则独立后 job_id 可能为空，不能直接按 job_id 过滤）
        if (request.getJobId() != null) {
            List<Long> ruleIds = listRuleIdsByJob(request.getJobId());
            if (ruleIds.isEmpty()) {
                return PageResult.of(List.of(), 0L, request.getPage(), request.getPageSize());
            }
            wrapper.in("id", ruleIds);
        }
        wrapper.orderByDesc("id");
        IPage<QualityRule> result = ruleMapper.selectPage(page, wrapper);
        List<QualityRuleDTO> dtos = buildDTOs(result.getRecords());
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    // ==================== 写操作 ====================

    /**
     * 新增规则（Sprint 7 支持独立创建：jobId 可空；有值时校验任务存在）。
     */
    @Transactional
    public QualityRuleDTO create(QualityRuleCreateRequest request) {
        Long jobId = request.getJobId();
        if (jobId != null) {
            requireJob(jobId);
        }
        validateType(request.getType());
        MetadataTable table = requireTable(request.getTableId());
        QualityRuleTemplate template = request.getTemplateId() == null
                ? null : templateMapper.selectById(request.getTemplateId());
        // 模板类规则（完整性/唯一性/值域）必须关联模板，否则执行时无 SQL 可生成（CUSTOM_SQL 用用户 SQL）
        if (!"CUSTOM_SQL".equals(request.getType()) && template == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND,
                    "规则类型 " + request.getType() + " 必须选择对应规则模板");
        }

        String name = request.getName().trim();
        if (countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "已存在同名规则: " + name);
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
        entity.setCreatedAt(LocalDateTime.now());
        ruleMapper.insert(entity);
        // 创建时若指定任务，同时写入关联表（历史「任务下建规则」流程兼容）
        if (jobId != null) {
            bindJobRule(jobId, entity.getId());
        }
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

        // 预计算每条规则名，先做批量重名校验（本次批量内部 + 库内已有同名），避免生成重复规则
        List<QualityRuleBatchCreateRequest.RuleItem> items = request.getItems();
        List<String> names = new ArrayList<>(items.size());
        for (QualityRuleBatchCreateRequest.RuleItem item : items) {
            names.add(resolveBatchName(template, tableMap.get(item.getTableId()), item, request));
        }
        assertNoDuplicateNames(names);
        Set<String> existingNames = ruleMapper.selectList(
                        new QueryWrapper<QualityRule>().select("name").in("name", names)).stream()
                .map(QualityRule::getName)
                .collect(Collectors.toSet());
        for (String name : names) {
            if (existingNames.contains(name)) {
                throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "已存在同名规则: " + name);
            }
        }

        Long currentUserId = currentUserId();
        LocalDateTime now = LocalDateTime.now();
        List<QualityRule> created = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            QualityRuleBatchCreateRequest.RuleItem item = items.get(i);
            validateItemForTemplate(template, item);

            QualityRule entity = new QualityRule();
            entity.setJobId(request.getJobId());
            entity.setTemplateId(template.getId());
            entity.setName(names.get(i));
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
            entity.setCreatedAt(now);
            created.add(entity);
        }
        // 批量插入（Db.saveBatch 自动填充 ASSIGN_ID 主键，避免逐条 insert）
        Db.saveBatch(created);
        // 批量写入任务<->规则关联（新规则必然未绑定，无需逐条幂等 selectCount）
        List<QualityJobRule> links = created.stream()
                .map(r -> {
                    QualityJobRule link = new QualityJobRule();
                    link.setJobId(request.getJobId());
                    link.setRuleId(r.getId());
                    link.setCreatedAt(now);
                    return link;
                })
                .collect(Collectors.toList());
        Db.saveBatch(links);
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
        if (!entity.getName().equals(name) && countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "已存在同名规则: " + name);
        }
        if (request.getTableId() != null) {
            requireTable(request.getTableId());
        }
        // 模板类规则（完整性/唯一性/值域）必须关联模板，否则执行时无 SQL 可生成（CUSTOM_SQL 用用户 SQL）
        if (!"CUSTOM_SQL".equals(request.getType())) {
            if (request.getTemplateId() == null) {
                throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND,
                        "规则类型 " + request.getType() + " 必须选择对应规则模板");
            }
            requireTemplate(request.getTemplateId());
        }

        // 更新语义：全量覆盖（规则编辑表单前端总是全量提交，DTO @AssertTrue 强校验完整数据；
        // RANGE 必填 columnName/rangeMin/rangeMax，非 RANGE 清理值域）
        entity.setName(name);
        entity.setType(request.getType().trim().toUpperCase());
        entity.setTemplateId(request.getTemplateId());
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
     * 删除规则（级联删除 quality_job_rule 关联）。
     * <p>
     * 删完后对规则所属表做「无启用规则」检查：若该表删除后不再有启用规则，
     * 则清理该表 quality_score（与删任务 cleanupScoresWithoutActiveRules 语义一致），
     * 避免残留孤儿评分。
     */
    @Transactional
    public void delete(Long id) {
        QualityRule entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        jobRuleMapper.delete(new QueryWrapper<QualityJobRule>().eq("rule_id", id));
        ruleMapper.deleteById(id);
        if (entity.getTableId() != null) {
            cleanupScoreIfNoActiveRule(entity.getTableId());
        }
    }

    /**
     * 若指定表已无任何启用规则，则删除该表 quality_score（清孤儿评分）。
     */
    private void cleanupScoreIfNoActiveRule(Long tableId) {
        Long activeRules = ruleMapper.selectCount(
                new QueryWrapper<QualityRule>().eq("table_id", tableId).eq("enabled", 1));
        if (activeRules == null || activeRules == 0) {
            int removed = qualityScoreMapper.delete(
                    new QueryWrapper<QualityScore>().eq("table_id", tableId));
            if (removed > 0) {
                log.info("删除质量规则后清理无规则覆盖表的评分: tableId={}, removed={}", tableId, removed);
            }
        }
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
     * 单条规则执行：触发 worker 上的质量执行 XXL-JOB 异步执行（param=rule:&lt;ruleId&gt;）。
     */
    public void executeRule(Long id) {
        requireRule(id);
        triggerService.triggerRule(id, "MANUAL");
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

    // ==================== 任务<->规则 关联（Sprint 7 多对多） ====================

    /**
     * 绑定一条规则到任务（幂等，已绑定则跳过）。
     */
    public void bindJobRule(Long jobId, Long ruleId) {
        if (jobId == null || ruleId == null) {
            return;
        }
        if (jobRuleMapper.selectCount(new QueryWrapper<QualityJobRule>()
                .eq("job_id", jobId).eq("rule_id", ruleId)) > 0) {
            return;
        }
        QualityJobRule link = new QualityJobRule();
        link.setJobId(jobId);
        link.setRuleId(ruleId);
        link.setCreatedAt(LocalDateTime.now());
        jobRuleMapper.insert(link);
    }

    /**
     * 全量覆盖任务引用的规则集合（先删后插）。
     */
    public void setJobRules(Long jobId, Collection<Long> ruleIds) {
        jobRuleMapper.delete(new QueryWrapper<QualityJobRule>().eq("job_id", jobId));
        if (ruleIds == null) {
            return;
        }
        for (Long ruleId : ruleIds) {
            if (ruleId != null) {
                bindJobRule(jobId, ruleId);
            }
        }
    }

    /**
     * 查询任务引用的规则 ID 集合。
     */
    public List<Long> listRuleIdsByJob(Long jobId) {
        if (jobId == null) {
            return List.of();
        }
        return jobRuleMapper.selectList(new QueryWrapper<QualityJobRule>()
                        .eq("job_id", jobId).orderByAsc("id")).stream()
                .map(QualityJobRule::getRuleId).toList();
    }

    /**
     * 批量查询多条规则被哪些任务引用，返回 ruleId → 任务名列表（供规则列表回填所属任务）。
     */
    public Map<Long, List<String>> listJobNamesByRuleIds(Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Map.of();
        }
        List<QualityJobRule> links = jobRuleMapper.selectList(new QueryWrapper<QualityJobRule>()
                .in("rule_id", ruleIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> jobIds = links.stream().map(QualityJobRule::getJobId).collect(Collectors.toSet());
        Map<Long, String> jobNameMap = jobIds.isEmpty() ? Map.of()
                : jobMapper.selectBatchIds(jobIds).stream()
                .collect(Collectors.toMap(QualityJob::getId, QualityJob::getName));
        Map<Long, List<String>> map = new HashMap<>();
        for (QualityJobRule link : links) {
            String jobName = jobNameMap.get(link.getJobId());
            map.computeIfAbsent(link.getRuleId(), k -> new ArrayList<>())
                    .add(jobName == null ? "" : jobName);
        }
        return map;
    }

    // ==================== 内部协作（供 QualityJobService 级联） ====================

    /**
     * 任务删除级联：删除任务的所有关联记录（规则本身保留，可被其他任务继续引用）。
     */
    public void deleteJobRules(Long jobId) {
        jobRuleMapper.delete(new QueryWrapper<QualityJobRule>().eq("job_id", jobId));
    }

    /**
     * 统计任务下规则数（任务列表冗余回填，经关联表统计）。
     */
    public long countByJob(Long jobId) {
        return jobRuleMapper.selectCount(new QueryWrapper<QualityJobRule>().eq("job_id", jobId));
    }

    /**
     * 批量统计多个任务下规则数（经关联表，避免 N+1）。返回 jobId → 规则数。
     */
    public Map<Long, Long> countByJobIds(List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<QualityJobRule> wrapper = new QueryWrapper<QualityJobRule>()
                .select("job_id AS job_id, COUNT(*) AS cnt")
                .in("job_id", jobIds)
                .groupBy("job_id");
        List<Map<String, Object>> rows = jobRuleMapper.selectMaps(wrapper);
        Map<Long, Long> map = new HashMap<>();
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

    private long countByName(String name) {
        return ruleMapper.selectCount(new QueryWrapper<QualityRule>().eq("name", name));
    }

    /**
     * 校验本次批量生成的规则名不重复（同名模板·同名表或用户自定义同名会撞名）。
     */
    private void assertNoDuplicateNames(List<String> names) {
        if (names.stream().distinct().count() != names.size()) {
            String dup = names.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("");
            throw new BusinessException(ErrorCode.QUALITY_RULE_NAME_EXISTS, "批量应用中存在重名规则: " + dup);
        }
    }

    /**
     * 批量构建 DTO，一次性回填表名/数据源名/模板名/用户名/所属任务名，避免 N+1。
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
        // 数据源名映射（经 datasource_connection 批量查；内置 Doris 特殊映射）
        Map<Long, String> datasourceNameMap = loadDatasourceNameMap(tableMap.values());
        // 模板信息
        Set<Long> templateIds = records.stream()
                .map(QualityRule::getTemplateId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QualityRuleTemplate> templateMap = templateIds.isEmpty()
                ? Map.of() : templateMapper.selectBatchIds(templateIds).stream()
                .collect(Collectors.toMap(QualityRuleTemplate::getId, Function.identity()));
        // 用户名映射
        Map<Long, String> usernameMap = loadUsernameMap(records);
        // 所属任务名映射（经关联表，ruleId → 任务名列表）
        Map<Long, List<String>> jobNameMap = listJobNamesByRuleIds(
                records.stream().map(QualityRule::getId).collect(Collectors.toSet()));

        return records.stream()
                .map(e -> toDTO(e, tableMap, datasourceNameMap, templateMap, usernameMap, jobNameMap))
                .toList();
    }

    private QualityRuleDTO toDTO(QualityRule entity, Map<Long, MetadataTable> tableMap,
                                 Map<Long, String> datasourceNameMap,
                                 Map<Long, QualityRuleTemplate> templateMap,
                                 Map<Long, String> usernameMap,
                                 Map<Long, List<String>> jobNameMap) {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setId(entity.getId());
        dto.setJobId(entity.getJobId());
        dto.setJobName(joinJobNames(jobNameMap.get(entity.getId())));
        dto.setTemplateId(entity.getTemplateId());
        QualityRuleTemplate template = entity.getTemplateId() == null ? null : templateMap.get(entity.getTemplateId());
        dto.setTemplateName(template == null ? null : template.getName());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setTableId(entity.getTableId());
        MetadataTable table = entity.getTableId() == null ? null : tableMap.get(entity.getTableId());
        dto.setTableName(table == null ? null : RuleSqlGenerator.buildFullTableName(table));
        dto.setDatabaseName(table == null ? null : table.getDatabaseName());
        dto.setSchemaName(table == null ? null : table.getSchemaName());
        dto.setDatasourceId(table == null ? null : table.getDatasourceId());
        dto.setDatasourceName(table == null ? null : datasourceNameMap.get(table.getDatasourceId()));
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

    /**
     * 多个任务名以「、」拼接展示（规则可被多任务引用）。
     */
    private String joinJobNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        return names.stream().filter(Objects::nonNull).filter(n -> !n.isBlank())
                .distinct().collect(Collectors.joining("、"));
    }

    private Map<Long, String> loadUsernameMap(List<QualityRule> records) {
        Set<Long> userIds = records.stream()
                .flatMap(e -> Stream.of(e.getCreatedBy(), e.getUpdatedBy()))
                .filter(Objects::nonNull).filter(id -> id > 0)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return usernames(userIds);
    }

    /**
     * 批量构建数据源名映射（datasourceId → 数据源名）。
     * 内置 Doris 表（datasourceId = -1）在 datasource_connection 中不存在，特殊映射为 "Doris 数仓"。
     */
    private Map<Long, String> loadDatasourceNameMap(Collection<MetadataTable> tables) {
        Set<Long> dsIds = tables.stream()
                .map(MetadataTable::getDatasourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (dsIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> externalIds = dsIds.stream().filter(id -> id != -1L).collect(Collectors.toSet());
        // 经 engineering 服务 Feign 批量回填数据源名；失败经 RemoteCalls 降级为空 Map（名称列退化），不阻断列表
        Map<Long, String> map = externalIds.isEmpty() ? new HashMap<>()
                : RemoteCalls.execute("engineering.datasource.batchGet", () -> {
                    IdsRequest request = new IdsRequest();
                    request.setIds(externalIds.stream().toList());
                    Result<Map<Long, DataSourceInfo>> result = datasourceApi.batchGet(request);
                    Map<Long, DataSourceInfo> data = result == null || result.data() == null
                            ? Map.<Long, DataSourceInfo>of() : result.data();
                    Map<Long, String> names = new HashMap<>();
                    data.forEach((id, info) -> names.put(id, info.getName()));
                    return names;
                }, new HashMap<>());
        if (dsIds.contains(-1L)) {
            map.put(-1L, "Doris 数仓");
        }
        return map;
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
