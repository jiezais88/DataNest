package com.datanest.task.core.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.dto.QualityRuleTemplateCreateRequest;
import com.datanest.task.core.dto.QualityRuleTemplateDTO;
import com.datanest.task.core.dto.QualityRuleTemplateQueryRequest;
import com.datanest.task.core.dto.QualityRuleTemplateUpdateRequest;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityRuleTemplate;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityRuleTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量规则模板服务（Sprint 6 规则模板库，D-D3 决策）。
 * <p>
 * 模板 = 校验逻辑模板（类型 + SQL 片段 + 字段/阈值占位符），任务内「选择模板 + 多表」
 * 批量生成 {@code quality_rule} 实例。内置四类模板不可删除；内置/自定义均可编辑与启停。
 * 本服务仅做模板 CRUD，批量应用（模板→规则实例）的关联逻辑由后续质量规则模块接入。
 */
@Service
public class QualityRuleTemplateService {

    /** 合法模板类型 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "COMPLETENESS", "UNIQUENESS", "RANGE", "CUSTOM_SQL"
    );

    private static final Logger logger = LoggerFactory.getLogger(QualityRuleTemplateService.class);

    private final QualityRuleTemplateMapper templateMapper;
    private final QualityRuleMapper qualityRuleMapper;
    private final SystemUserApi systemUserApi;

    public QualityRuleTemplateService(QualityRuleTemplateMapper templateMapper,
                                      QualityRuleMapper qualityRuleMapper,
                                      SystemUserApi systemUserApi) {
        this.templateMapper = templateMapper;
        this.qualityRuleMapper = qualityRuleMapper;
        this.systemUserApi = systemUserApi;
    }

    // ==================== 查询 ====================

    /**
     * 全量模板列表（含内置，供批量应用下拉选择等）。可按类型过滤。
     */
    public List<QualityRuleTemplateDTO> listAll(String type) {
        QueryWrapper<QualityRuleTemplate> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(type)) {
            wrapper.eq("type", type.trim().toUpperCase());
        }
        wrapper.eq("enabled", 1);
        wrapper.orderByDesc("builtin").orderByAsc("id");
        List<QualityRuleTemplate> records = templateMapper.selectList(wrapper);
        Map<Long, String> usernameMap = loadUsernameMap(records);
        return records.stream().map(e -> toDTO(e, usernameMap)).toList();
    }

    /**
     * 分页列表，支持关键字 / 类型 / 内置 / 启用过滤。
     */
    public PageResult<QualityRuleTemplateDTO> list(QualityRuleTemplateQueryRequest request) {
        IPage<QualityRuleTemplate> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<QualityRuleTemplate> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim());
        }
        if (request.getType() != null && !request.getType().isBlank()) {
            wrapper.eq("type", request.getType().trim().toUpperCase());
        }
        if (request.getBuiltin() != null) {
            wrapper.eq("builtin", request.getBuiltin());
        }
        if (request.getEnabled() != null) {
            wrapper.eq("enabled", request.getEnabled());
        }
        wrapper.orderByDesc("builtin").orderByAsc("id");
        IPage<QualityRuleTemplate> result = templateMapper.selectPage(page, wrapper);

        Map<Long, String> usernameMap = loadUsernameMap(result.getRecords());
        List<QualityRuleTemplateDTO> dtos = result.getRecords().stream()
                .map(e -> toDTO(e, usernameMap))
                .toList();
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public QualityRuleTemplateDTO getById(Long id) {
        QualityRuleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND, "质量规则模板不存在: " + id);
        }
        return toDTO(entity, loadUsernameMap(List.of(entity)));
    }

    // ==================== 写操作 ====================

    @Transactional
    public QualityRuleTemplateDTO create(QualityRuleTemplateCreateRequest request) {
        validateType(request.getType());
        String name = request.getName().trim();
        if (countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NAME_EXISTS, "模板名称已存在: " + name);
        }

        QualityRuleTemplate entity = new QualityRuleTemplate();
        applyFields(entity, name, request.getType(), request.getDescription(),
                request.getSqlTemplate(), request.getResultMetric(),
                request.getEnabled() == null ? 1 : request.getEnabled());
        entity.setBuiltin(0); // 新增模板一律为自定义
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        templateMapper.insert(entity);
        return toDTO(entity, Map.of());
    }

    @Transactional
    public QualityRuleTemplateDTO update(Long id, QualityRuleTemplateUpdateRequest request) {
        QualityRuleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND, "质量规则模板不存在: " + id);
        }
        validateType(request.getType());
        String name = request.getName().trim();
        if (!entity.getName().equals(name) && countByName(name) > 0) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NAME_EXISTS, "模板名称已存在: " + name);
        }

        applyFields(entity, name, request.getType(), request.getDescription(),
                request.getSqlTemplate(), request.getResultMetric(),
                request.getEnabled() == null ? entity.getEnabled() : request.getEnabled());
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(entity);
        return toDTO(entity, loadUsernameMap(List.of(entity)));
    }

    @Transactional
    public void delete(Long id) {
        QualityRuleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND, "质量规则模板不存在: " + id);
        }
        if (entity.getBuiltin() != null && entity.getBuiltin() == 1) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_BUILTIN_NOT_DELETE, "内置模板不可删除");
        }
        // 删除关联校验：模板被质量规则（template_id）引用时阻止删除，避免规则指向已删除模板
        Long referenced = qualityRuleMapper.selectCount(
                new QueryWrapper<QualityRule>().eq("template_id", id));
        if (referenced != null && referenced > 0) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES,
                    "规则模板已被质量规则引用，请先删除相关规则");
        }
        templateMapper.deleteById(id);
    }

    /**
     * 启停模板（内置/自定义均可停用；停用后不可再被批量应用选用）。
     * enabled 为空时视为切换（取反当前状态）。
     */
    @Transactional
    public QualityRuleTemplateDTO toggle(Long id, Boolean enabled) {
        QualityRuleTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_NOT_FOUND, "质量规则模板不存在: " + id);
        }
        boolean target = enabled != null ? enabled : (entity.getEnabled() == null || entity.getEnabled() != 1);
        entity.setEnabled(target ? 1 : 0);
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(entity);
        return toDTO(entity, loadUsernameMap(List.of(entity)));
    }

    // ==================== private ====================

    private void validateType(String type) {
        if (!StringUtils.hasText(type) || !SUPPORTED_TYPES.contains(type.trim().toUpperCase())) {
            throw new BusinessException(ErrorCode.QUALITY_TEMPLATE_TYPE_INVALID,
                    "模板类型非法: " + type + "（仅支持 " + SUPPORTED_TYPES + "）");
        }
    }

    private long countByName(String name) {
        return templateMapper.selectCount(new QueryWrapper<QualityRuleTemplate>().eq("name", name));
    }

    private void applyFields(QualityRuleTemplate entity, String name, String type, String description,
                             String sqlTemplate, String resultMetric, Integer enabled) {
        entity.setName(name == null ? null : name.trim());
        entity.setType(type.trim().toUpperCase());
        entity.setDescription(description);
        entity.setSqlTemplate(sqlTemplate);
        entity.setResultMetric(resultMetric);
        entity.setEnabled(enabled);
    }

    /**
     * 批量回填创建人/修改人用户名映射（避免 N+1：一次查全部 userId → username）。
     */
    private Map<Long, String> loadUsernameMap(List<QualityRuleTemplate> records) {
        if (records == null || records.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = records.stream()
                .flatMap(e -> Stream.of(e.getCreatedBy(), e.getUpdatedBy()))
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return usernames(userIds);
    }

    private QualityRuleTemplateDTO toDTO(QualityRuleTemplate entity, Map<Long, String> usernameMap) {
        QualityRuleTemplateDTO dto = new QualityRuleTemplateDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setDescription(entity.getDescription());
        dto.setSqlTemplate(entity.getSqlTemplate());
        dto.setResultMetric(entity.getResultMetric());
        dto.setBuiltin(entity.getBuiltin());
        dto.setEnabled(entity.getEnabled());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        // usernameMap 可能是不可变 Map（如 Map.of()），不允许 get(null)，需先判空
        dto.setCreatedByName(entity.getCreatedBy() == null ? null : usernameMap.get(entity.getCreatedBy()));
        dto.setUpdatedByName(entity.getUpdatedBy() == null ? null : usernameMap.get(entity.getUpdatedBy()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
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
        try {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        } catch (Exception e) {
            logger.warn("查询用户名映射失败，降级为空: {}", e.toString());
            return Map.of();
        }
    }
}
