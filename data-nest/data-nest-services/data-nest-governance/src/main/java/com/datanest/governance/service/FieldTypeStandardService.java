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
import com.datanest.task.core.entity.FieldTypeStandard;
import com.datanest.task.core.entity.NamingStandard;
import com.datanest.task.core.mapper.FieldTypeStandardMapper;
import com.datanest.task.core.mapper.NamingStandardMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FieldTypeStandardService {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeStandardService.class);

    private final FieldTypeStandardMapper fieldTypeStandardMapper;
    private final NamingStandardMapper namingStandardMapper;
    private final SystemUserApi systemUserApi;

    public FieldTypeStandardService(FieldTypeStandardMapper fieldTypeStandardMapper,
                                    NamingStandardMapper namingStandardMapper,
                                    SystemUserApi systemUserApi) {
        this.fieldTypeStandardMapper = fieldTypeStandardMapper;
        this.namingStandardMapper = namingStandardMapper;
        this.systemUserApi = systemUserApi;
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
        entity.setCreatedAt(LocalDateTime.now());
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
        List<NamingStandard> referencing = namingStandardMapper.selectList(
                new QueryWrapper<NamingStandard>().eq("target_standard_id", id).select("name"));
        if (!referencing.isEmpty()) {
            List<String> refNames = referencing.stream().map(NamingStandard::getName).toList();
            throw new BusinessException(ErrorCode.HAS_REFERENCES, "字段类型标准被命名规范引用，无法删除", refNames);
        }
        fieldTypeStandardMapper.deleteById(id);
    }

    public FieldTypeStandardDTO getById(Long id) {
        FieldTypeStandard entity = fieldTypeStandardMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.FIELD_TYPE_STANDARD_NOT_FOUND);
        }
        Map<Long, String> usernameMap = usernames(
                List.of(entity.getCreatedBy(), entity.getUpdatedBy()));
        return toDTO(entity, usernameMap);
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
        List<Long> userIds = result.getRecords().stream()
                .flatMap(e -> Stream.of(e.getCreatedBy(), e.getUpdatedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = usernames(userIds);
        List<FieldTypeStandardDTO> records = result.getRecords().stream()
                .map(e -> toDTO(e, usernameMap))
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private long countByName(String name) {
        return fieldTypeStandardMapper.selectCount(new QueryWrapper<FieldTypeStandard>().eq("name", name));
    }

    private FieldTypeStandardDTO toDTO(FieldTypeStandard entity) {
        return toDTO(entity, Map.of());
    }

    private FieldTypeStandardDTO toDTO(FieldTypeStandard entity, Map<Long, String> usernameMap) {
        FieldTypeStandardDTO dto = new FieldTypeStandardDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setAllowedTypes(entity.getAllowedTypes());
        dto.setDescription(entity.getDescription());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setCreatedByName(usernameMap.get(entity.getCreatedBy()));
        dto.setUpdatedByName(usernameMap.get(entity.getUpdatedBy()));
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
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常（如序列化错），warn + 计数后返回空 Map
        return RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        }, Map.of());
    }
}
