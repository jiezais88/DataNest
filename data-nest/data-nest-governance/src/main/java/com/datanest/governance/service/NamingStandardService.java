package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.governance.dto.NamingStandardCreateRequest;
import com.datanest.governance.dto.NamingStandardDTO;
import com.datanest.governance.dto.NamingStandardQueryRequest;
import com.datanest.governance.dto.NamingStandardUpdateRequest;
import com.datanest.governance.entity.NamingStandard;
import com.datanest.governance.mapper.ComplianceCheckResultMapper;
import com.datanest.governance.mapper.FieldTypeStandardMapper;
import com.datanest.governance.mapper.NamingStandardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Service
public class NamingStandardService {

    private final NamingStandardMapper namingStandardMapper;
    private final FieldTypeStandardMapper fieldTypeStandardMapper;
    private final ComplianceCheckResultMapper complianceCheckResultMapper;

    public NamingStandardService(NamingStandardMapper namingStandardMapper,
                                 FieldTypeStandardMapper fieldTypeStandardMapper,
                                 ComplianceCheckResultMapper complianceCheckResultMapper) {
        this.namingStandardMapper = namingStandardMapper;
        this.fieldTypeStandardMapper = fieldTypeStandardMapper;
        this.complianceCheckResultMapper = complianceCheckResultMapper;
    }

    @Transactional
    public NamingStandardDTO create(NamingStandardCreateRequest request) {
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.NAMING_STANDARD_NAME_EXISTS);
        }
        checkTargetStandard(request.getTargetStandardId());
        validateNamingStandardRequest(request.getAppliesTo(), request.getRuleType(), request.getTargetStandardId());
        validateRegexRule(request.getRuleType(), request.getRuleValue());

        NamingStandard entity = new NamingStandard();
        entity.setName(request.getName());
        entity.setAppliesTo(request.getAppliesTo());
        entity.setRuleType(request.getRuleType());
        entity.setRuleValue(request.getRuleValue());
        entity.setTargetStandardId(request.getTargetStandardId());
        entity.setPriority(request.getPriority());
        entity.setEnabled(request.getEnabled());
        entity.setDescription(request.getDescription());
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        namingStandardMapper.insert(entity);
        return toDTO(entity);
    }

    @Transactional
    public NamingStandardDTO update(Long id, NamingStandardUpdateRequest request) {
        NamingStandard entity = namingStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NAMING_STANDARD_NOT_FOUND);
        }
        if (!entity.getName().equals(request.getName()) && countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.NAMING_STANDARD_NAME_EXISTS);
        }
        checkTargetStandard(request.getTargetStandardId());
        validateNamingStandardRequest(request.getAppliesTo(), request.getRuleType(), request.getTargetStandardId());
        validateRegexRule(request.getRuleType(), request.getRuleValue());

        entity.setName(request.getName());
        entity.setAppliesTo(request.getAppliesTo());
        entity.setRuleType(request.getRuleType());
        entity.setRuleValue(request.getRuleValue());
        entity.setTargetStandardId(request.getTargetStandardId());
        entity.setPriority(request.getPriority());
        entity.setEnabled(request.getEnabled());
        entity.setDescription(request.getDescription());
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        namingStandardMapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        NamingStandard entity = namingStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NAMING_STANDARD_NOT_FOUND);
        }
        complianceCheckResultMapper.delete(new QueryWrapper<com.datanest.governance.entity.ComplianceCheckResult>().eq("standard_id", id));
        namingStandardMapper.deleteById(id);
    }

    public NamingStandardDTO getById(Long id) {
        NamingStandard entity = namingStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NAMING_STANDARD_NOT_FOUND);
        }
        return toDTO(entity);
    }

    public PageResult<NamingStandardDTO> list(NamingStandardQueryRequest request) {
        IPage<NamingStandard> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<NamingStandard> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim());
        }
        if (request.getAppliesTo() != null && !request.getAppliesTo().isBlank()) {
            wrapper.eq("applies_to", request.getAppliesTo());
        }
        if (request.getEnabled() != null) {
            wrapper.eq("enabled", request.getEnabled());
        }
        wrapper.orderByDesc("priority").orderByAsc("id");
        IPage<NamingStandard> result = namingStandardMapper.selectPage(page, wrapper);

        Map<Long, String> standardNameMap = fieldTypeStandardMapper.selectBatchIds(
                result.getRecords().stream().map(NamingStandard::getTargetStandardId).distinct().toList()
        ).stream().collect(Collectors.toMap(
                com.datanest.governance.entity.FieldTypeStandard::getId,
                com.datanest.governance.entity.FieldTypeStandard::getName
        ));

        List<NamingStandardDTO> records = result.getRecords().stream()
                .map(e -> {
                    NamingStandardDTO dto = toDTO(e);
                    dto.setTargetStandardName(standardNameMap.get(e.getTargetStandardId()));
                    return dto;
                })
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public List<NamingStandard> listEnabledByAppliesTo(String appliesTo) {
        return namingStandardMapper.selectEnabledByAppliesTo(appliesTo);
    }

    private void checkTargetStandard(Long targetStandardId) {
        if (targetStandardId != null && fieldTypeStandardMapper.selectById(targetStandardId) == null) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NOT_FOUND);
        }
    }

    private void validateNamingStandardRequest(String appliesTo, String ruleType, Long targetStandardId) {
        if (!"TABLE".equals(appliesTo) && !"COLUMN".equals(appliesTo)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "适用对象只能是 TABLE 或 COLUMN");
        }
        if (!"PREFIX".equals(ruleType) && !"SUFFIX".equals(ruleType) && !"REGEX".equals(ruleType)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规则类型只能是 PREFIX、SUFFIX 或 REGEX");
        }
        if ("COLUMN".equals(appliesTo) && targetStandardId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "字段级命名规范必须关联字段类型标准");
        }
    }

    private void validateRegexRule(String ruleType, String ruleValue) {
        if ("REGEX".equals(ruleType)) {
            try {
                Pattern.compile(ruleValue);
            } catch (PatternSyntaxException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "正则表达式语法错误: " + e.getMessage());
            }
        }
    }

    private long countByName(String name) {
        return namingStandardMapper.selectCount(new QueryWrapper<NamingStandard>().eq("name", name));
    }

    private NamingStandardDTO toDTO(NamingStandard entity) {
        NamingStandardDTO dto = new NamingStandardDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setAppliesTo(entity.getAppliesTo());
        dto.setRuleType(entity.getRuleType());
        dto.setRuleValue(entity.getRuleValue());
        dto.setTargetStandardId(entity.getTargetStandardId());
        dto.setPriority(entity.getPriority());
        dto.setEnabled(entity.getEnabled());
        dto.setDescription(entity.getDescription());
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
}
