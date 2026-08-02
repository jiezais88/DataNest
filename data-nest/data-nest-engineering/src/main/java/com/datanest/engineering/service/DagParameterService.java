package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.dto.DagParameterPayload;
import com.datanest.task.core.entity.DagParameter;
import com.datanest.task.core.mapper.DagParameterMapper;
import com.datanest.task.core.service.DagParameterResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DAG 参数服务：CRUD、参数解析、占位符替换
 */
@Service
public class DagParameterService {

    private final DagParameterMapper dagParameterMapper;
    private final DagParameterResolver dagParameterResolver;

    public DagParameterService(DagParameterMapper dagParameterMapper,
                               DagParameterResolver dagParameterResolver) {
        this.dagParameterMapper = dagParameterMapper;
        this.dagParameterResolver = dagParameterResolver;
    }

    public List<DagParameterPayload> listByDagId(Long dagId) {
        List<DagParameter> list = dagParameterMapper.selectByDagId(dagId);
        return list.stream().map(this::toPayload).toList();
    }

    public DagParameterPayload getById(Long id) {
        DagParameter p = dagParameterMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "参数不存在: " + id);
        }
        return toPayload(p);
    }

    @Transactional
    public DagParameterPayload create(Long dagId, DagParameterPayload payload) {
        validateParam(payload);
        DagParameter p = new DagParameter();
        p.setDagId(dagId);
        p.setParamName(payload.getParamName());
        p.setParamType(payload.getParamType());
        p.setDefaultValue(payload.getDefaultValue());
        p.setRequired(Boolean.TRUE.equals(payload.getRequired()) ? 1 : 0);
        p.setDescription(payload.getDescription());
        p.setCreatedBy(currentUserId());
        p.setUpdatedBy(currentUserId());
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        dagParameterMapper.insert(p);
        return toPayload(p);
    }

    @Transactional
    public DagParameterPayload update(Long dagId, Long id, DagParameterPayload payload) {
        DagParameter p = dagParameterMapper.selectById(id);
        if (p == null || !dagId.equals(p.getDagId())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "参数不存在或不属于该 DAG: " + id);
        }
        validateParam(payload);
        p.setParamName(payload.getParamName());
        p.setParamType(payload.getParamType());
        p.setDefaultValue(payload.getDefaultValue());
        p.setRequired(Boolean.TRUE.equals(payload.getRequired()) ? 1 : 0);
        p.setDescription(payload.getDescription());
        p.setUpdatedBy(currentUserId());
        p.setUpdatedAt(LocalDateTime.now());
        dagParameterMapper.updateById(p);
        return toPayload(p);
    }

    @Transactional
    public void delete(Long dagId, Long id) {
        DagParameter p = dagParameterMapper.selectById(id);
        if (p == null || !dagId.equals(p.getDagId())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "参数不存在或不属于该 DAG: " + id);
        }
        dagParameterMapper.deleteById(id);
    }

    /**
     * 解析 DAG 执行参数：手动覆盖 > 默认值 > 系统变量
     *
     * @param dagId          DAG ID
     * @param manualOverrides 手动传入的参数
     * @return 合并后的参数 Map
     */
    public Map<String, Object> resolveParams(Long dagId, Map<String, Object> manualOverrides) {
        return dagParameterResolver.resolveParams(dagId, manualOverrides);
    }

    /**
     * 替换字符串中的 ${key} 占位符。
     * 若值用于 SQL 字符串，会自动对单引号转义。
     * 未定义的占位符保留原样并记录 warn。
     */
    public String replacePlaceholders(String raw, Map<String, Object> params) {
        return dagParameterResolver.replacePlaceholders(raw, params);
    }

    /**
     * 批量替换 Map 中所有字符串值里的占位符。
     */
    public Map<String, Object> replacePlaceholders(Map<String, Object> rawMap, Map<String, Object> params) {
        return dagParameterResolver.replacePlaceholders(rawMap, params);
    }

    private void validateParam(DagParameterPayload payload) {
        if (!StringUtils.hasText(payload.getParamName())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "参数名不能为空");
        }
        if (!StringUtils.hasText(payload.getParamType())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "参数类型不能为空");
        }
        String type = payload.getParamType().toUpperCase();
        if (!List.of("STRING", "NUMBER", "DATE", "BOOLEAN").contains(type)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "不支持的参数类型: " + payload.getParamType());
        }
    }

    private DagParameterPayload toPayload(DagParameter p) {
        DagParameterPayload dto = new DagParameterPayload();
        dto.setId(p.getId());
        dto.setDagId(p.getDagId());
        dto.setParamName(p.getParamName());
        dto.setParamType(p.getParamType());
        dto.setDefaultValue(p.getDefaultValue());
        dto.setRequired(p.getRequired() != null && p.getRequired() == 1);
        dto.setDescription(p.getDescription());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setUpdatedAt(p.getUpdatedAt());
        return dto;
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
