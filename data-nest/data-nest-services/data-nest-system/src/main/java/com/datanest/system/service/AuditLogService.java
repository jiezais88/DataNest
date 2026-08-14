package com.datanest.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.system.entity.AuditLog;
import com.datanest.system.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 审计日志服务（Sprint 11 F1）。
 * <p>
 * 写入时回填 operator_name（切面尽力带 username，缺省时按 operatorId 查 sys_user 回填）；
 * 提供分页查询（仅超管）、详情、90 天清理。
 */
@Service
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final SysUserService sysUserService;

    public AuditLogService(AuditLogMapper auditLogMapper, SysUserService sysUserService) {
        this.auditLogMapper = auditLogMapper;
        this.sysUserService = sysUserService;
    }

    /** 写入一条审计记录（internal 端点 + 本服务 Recorder 共用） */
    public void record(AuditLogEvent event) {
        AuditLog entity = new AuditLog();
        entity.setOperatorId(event.operatorId());
        entity.setOperatorName(resolveOperatorName(event.operatorId(), event.operatorName()));
        entity.setOpType(event.opType());
        entity.setResourceType(event.resourceType());
        entity.setResourceId(event.resourceId());
        entity.setResourceName(resolveResourceName(event.resourceType(), event.resourceId(), event.resourceName()));
        entity.setContent(event.content());
        entity.setResult(event.result());
        entity.setErrorMessage(event.errorMessage());
        entity.setClientIp(event.clientIp());
        entity.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(entity);
    }

    /** 分页查询（操作人/类型/资源类型/时间范围/关键词组合筛选，默认按时间倒序） */
    public PageResult<AuditLog> pageQuery(int page, int pageSize, String operatorName, String opType,
                                          String resourceType, String startTime, String endTime, String keyword) {
        Page<AuditLog> mpPage = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(operatorName)) {
            wrapper.like(AuditLog::getOperatorName, operatorName.trim());
        }
        if (StringUtils.hasText(opType)) {
            wrapper.eq(AuditLog::getOpType, opType.trim());
        }
        if (StringUtils.hasText(resourceType)) {
            wrapper.eq(AuditLog::getResourceType, resourceType.trim());
        }
        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        if (start != null) {
            wrapper.ge(AuditLog::getCreatedAt, start);
        }
        if (end != null) {
            wrapper.le(AuditLog::getCreatedAt, end);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(AuditLog::getResourceName, kw).or().like(AuditLog::getContent, kw));
        }
        wrapper.orderByDesc(AuditLog::getCreatedAt);
        IPage<AuditLog> result = auditLogMapper.selectPage(mpPage, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /** 审计详情 */
    public AuditLog detail(Long id) {
        AuditLog entity = auditLogMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "审计记录不存在");
        }
        return entity;
    }

    /** 清理保留天数之前的记录（job 定时 + internal 兜底），返回删除条数 */
    public int cleanup(int retainDays) {
        return auditLogMapper.deleteBefore(LocalDateTime.now().minusDays(retainDays));
    }

    private String resolveOperatorName(Long operatorId, String operatorName) {
        if (StringUtils.hasText(operatorName)) {
            return operatorName;
        }
        if (operatorId == null) {
            return null;
        }
        return sysUserService.getUsernameMap(List.of(operatorId)).get(operatorId);
    }

    /** 资源名称兜底：用户类操作（如启停/重置密码仅传 userId）落库时按 resourceId 回填用户名 */
    private String resolveResourceName(String resourceType, String resourceId, String resourceName) {
        if (StringUtils.hasText(resourceName) || !"USER".equals(resourceType) || !StringUtils.hasText(resourceId)) {
            return resourceName;
        }
        try {
            long userId = Long.parseLong(resourceId);
            return sysUserService.getUsernameMap(List.of(userId)).get(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 容错解析：优先 ISO 本地时间，其次纯日期（补 00:00:00），失败返回 null 忽略该条件 */
    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String v = value.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(v).atStartOfDay();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
