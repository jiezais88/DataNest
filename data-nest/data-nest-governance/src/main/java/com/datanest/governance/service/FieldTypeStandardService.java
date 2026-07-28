package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.governance.dto.FieldTypeStandardCreateRequest;
import com.datanest.governance.dto.FieldTypeStandardDTO;
import com.datanest.governance.dto.FieldTypeStandardQueryRequest;
import com.datanest.governance.dto.FieldTypeStandardUpdateRequest;
import com.datanest.governance.entity.FieldTypeStandard;
import com.datanest.governance.mapper.FieldTypeStandardMapper;
import com.datanest.governance.mapper.NamingStandardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FieldTypeStandardService {

    private final FieldTypeStandardMapper fieldTypeStandardMapper;
    private final NamingStandardMapper namingStandardMapper;

    public FieldTypeStandardService(FieldTypeStandardMapper fieldTypeStandardMapper,
                                    NamingStandardMapper namingStandardMapper) {
        this.fieldTypeStandardMapper = fieldTypeStandardMapper;
        this.namingStandardMapper = namingStandardMapper;
    }

    @Transactional
    public FieldTypeStandardDTO create(FieldTypeStandardCreateRequest request) {
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NAME_EXISTS);
        }
        FieldTypeStandard entity = new FieldTypeStandard();
        entity.setName(request.getName());
        entity.setCategory(request.getCategory());
        entity.setAllowedTypes(request.getAllowedTypes());
        entity.setDescription(request.getDescription());
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        fieldTypeStandardMapper.insert(entity);
        return toDTO(entity);
    }

    @Transactional
    public FieldTypeStandardDTO update(Long id, FieldTypeStandardUpdateRequest request) {
        FieldTypeStandard entity = fieldTypeStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NOT_FOUND);
        }
        if (!entity.getName().equals(request.getName()) && countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NAME_EXISTS);
        }
        entity.setName(request.getName());
        entity.setCategory(request.getCategory());
        entity.setAllowedTypes(request.getAllowedTypes());
        entity.setDescription(request.getDescription());
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        fieldTypeStandardMapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        FieldTypeStandard entity = fieldTypeStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NOT_FOUND);
        }
        long refCount = namingStandardMapper.selectCount(
                new QueryWrapper<com.datanest.governance.entity.NamingStandard>().eq("target_standard_id", id));
        if (refCount > 0) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES, "字段类型标准被命名规范引用，无法删除");
        }
        fieldTypeStandardMapper.deleteById(id);
    }

    public FieldTypeStandardDTO getById(Long id) {
        FieldTypeStandard entity = fieldTypeStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NOT_FOUND);
        }
        return toDTO(entity);
    }

    public PageResult<FieldTypeStandardDTO> list(FieldTypeStandardQueryRequest request) {
        IPage<FieldTypeStandard> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<FieldTypeStandard> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            wrapper.eq("category", request.getCategory());
        }
        wrapper.orderByDesc("created_at");
        IPage<FieldTypeStandard> result = fieldTypeStandardMapper.selectPage(page, wrapper);
        List<FieldTypeStandardDTO> records = result.getRecords().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private long countByName(String name) {
        return fieldTypeStandardMapper.selectCount(new QueryWrapper<FieldTypeStandard>().eq("name", name));
    }

    private FieldTypeStandardDTO toDTO(FieldTypeStandard entity) {
        FieldTypeStandardDTO dto = new FieldTypeStandardDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setAllowedTypes(entity.getAllowedTypes());
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
