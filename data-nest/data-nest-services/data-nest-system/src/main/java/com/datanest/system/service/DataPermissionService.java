package com.datanest.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.DataPermissionGrant;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.system.DataScope;
import com.datanest.system.dto.DataPermissionSaveRequest;
import com.datanest.system.dto.DataPermissionVO;
import com.datanest.system.entity.DataPermission;
import com.datanest.system.entity.Role;
import com.datanest.system.mapper.DataPermissionMapper;
import com.datanest.system.mapper.RoleMapper;
import com.datanest.system.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据权限服务（Sprint 11 F2，技术文档 D-2）。
 * <p>
 * 语义：角色无任何记录 = 全量可见（默认）；有记录 = 白名单过滤，最细粒度优先。
 * 提供「保存/查询角色白名单」与「查询用户合并数据权限范围（internal 端点复用）」。
 */
@Service
public class DataPermissionService {

    private final DataPermissionMapper dataPermissionMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final RoleService roleService;
    private final AuditLogRecorder auditLogRecorder;

    public DataPermissionService(DataPermissionMapper dataPermissionMapper, UserMapper userMapper,
                                 RoleMapper roleMapper, RoleService roleService, AuditLogRecorder auditLogRecorder) {
        this.dataPermissionMapper = dataPermissionMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.roleService = roleService;
        this.auditLogRecorder = auditLogRecorder;
    }

    /** 保存角色数据权限（全量重建；dataScope=FULL 时清空白名单，WHITELIST 时按 grants 重建） */
    @Transactional
    public void save(DataPermissionSaveRequest req) {
        Role role = roleService.getRole(req.roleId());
        if ("SUPER_ADMIN".equals(role.getCode())) {
            throw new BusinessException(ErrorCode.DATA_PERMISSION_INVALID, "超级管理员默认全量访问，不可配置数据权限");
        }
        String scope = req.dataScope();
        if (!DataScope.FULL.equals(scope) && !DataScope.WHITELIST.equals(scope)) {
            throw new BusinessException(ErrorCode.DATA_PERMISSION_INVALID, "dataScope 仅支持 FULL / WHITELIST");
        }

        // 显式持久化默认范围
        role.setDataScope(scope);
        roleMapper.updateById(role);

        // 白名单全量重建：FULL 时清空，WHITELIST 时按 grants 写入
        dataPermissionMapper.deleteByRoleId(req.roleId());
        if (DataScope.WHITELIST.equals(scope) && req.grants() != null && !req.grants().isEmpty()) {
            List<DataPermission> entities = new ArrayList<>();
            for (DataPermissionGrant g : req.grants().stream().distinct().toList()) {
                DataPermission p = new DataPermission();
                p.setRoleId(req.roleId());
                p.setDatasourceId(g.datasourceId());
                p.setDatabaseName(g.databaseName());
                p.setTableName(g.tableName());
                p.setCreatedAt(LocalDateTime.now());
                entities.add(p);
            }
            Db.saveBatch(entities);
        }
        // 权限变更审计（PRD §6.1.1 第 2 类），手动埋点记录角色名，fail-open
        writePermissionAudit(role);
    }

    /** 权限配置保存审计（resourceType=ROLE，权限变更类），fail-open */
    private void writePermissionAudit(Role role) {
        try {
            auditLogRecorder.record(new AuditLogEvent(
                    currentUserId(), null,
                    AuditOpType.UPDATE.name(), AuditResourceType.ROLE.name(),
                    String.valueOf(role.getId()), role.getName(),
                    "数据权限配置", AuditLogEvent.RESULT_SUCCESS, null, null));
        } catch (Exception e) {
            // fail-open：审计失败不影响权限配置主链路
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** 查询角色数据权限白名单（权限配置页回显） */
    public List<DataPermissionVO> listByRole(Long roleId) {
        roleService.getRole(roleId);
        List<DataPermission> list = dataPermissionMapper.selectList(
                new LambdaQueryWrapper<DataPermission>().eq(DataPermission::getRoleId, roleId));
        return list.stream()
                .map(p -> new DataPermissionVO(p.getId(), p.getDatasourceId(), p.getDatabaseName(), p.getTableName()))
                .toList();
    }

    /** 查询用户全部角色合并后的数据权限范围（internal 端点） */
    public UserDataPermissionDTO getUserDataPermission(Long userId) {
        List<Long> roleIds = userMapper.selectRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return UserDataPermissionDTO.fullAccess();
        }
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        // 任一角色 data_scope=FULL（含 null，向后兼容）即全量放行
        boolean anyFull = roles.stream().anyMatch(r -> !DataScope.WHITELIST.equals(r.getDataScope()));
        if (anyFull) {
            return UserDataPermissionDTO.fullAccess();
        }
        // 全部角色均为 WHITELIST：合并白名单（可能为空 = 什么都不可见）
        List<DataPermission> perms = dataPermissionMapper.selectList(
                new LambdaQueryWrapper<DataPermission>().in(DataPermission::getRoleId, roleIds));
        List<DataPermissionGrant> grants = perms.stream()
                .map(p -> new DataPermissionGrant(p.getDatasourceId(), p.getDatabaseName(), p.getTableName()))
                .distinct()
                .collect(Collectors.toList());
        return new UserDataPermissionDTO(false, grants);
    }
}
