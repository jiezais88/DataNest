package com.datanest.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.DataScope;
import com.datanest.system.dto.RoleCreateRequest;
import com.datanest.system.dto.RoleUpdateRequest;
import com.datanest.system.dto.RoleVO;
import com.datanest.system.dto.UserOptionDTO;
import com.datanest.system.entity.Permission;
import com.datanest.system.entity.Role;
import com.datanest.system.entity.RolePermission;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.DataPermissionMapper;
import com.datanest.system.mapper.PermissionMapper;
import com.datanest.system.mapper.RoleMapper;
import com.datanest.system.mapper.RolePermissionMapper;
import com.datanest.system.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 角色服务（Sprint 11 F2）。
 * <p>
 * 预置 4 角色只读不可删、权限矩阵固化（Flyway 种子）；自定义角色支持创建/编辑/删除 + 功能权限点勾选。
 * 角色增删改均记审计（Controller 层 @AuditLog，resourceType=ROLE）。
 */
@Service
public class RoleService {

    /** 预置角色 code（只读，不可编辑/删除） */
    public static final Set<String> BUILTIN_ROLE_CODES =
            Set.of("SUPER_ADMIN", "DATA_ENGINEER", "DATA_ANALYST", "GOVERNANCE_ADMIN");

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final DataPermissionMapper dataPermissionMapper;
    private final UserMapper userMapper;
    private final IdentifierGenerator idGenerator;
    private final AuditLogRecorder auditLogRecorder;
    private final SessionPermissionRefresher sessionPermissionRefresher;

    public RoleService(RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                       PermissionMapper permissionMapper, DataPermissionMapper dataPermissionMapper,
                       UserMapper userMapper, IdentifierGenerator idGenerator,
                       AuditLogRecorder auditLogRecorder, SessionPermissionRefresher sessionPermissionRefresher) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.dataPermissionMapper = dataPermissionMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
        this.auditLogRecorder = auditLogRecorder;
        this.sessionPermissionRefresher = sessionPermissionRefresher;
    }

    /** 角色列表（预置 + 自定义，含功能权限点） */
    public List<RoleVO> listRoles() {
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getCode));
        return roles.stream().map(this::toVO).toList();
    }

    /** 创建自定义角色 */
    @Transactional
    public RoleVO createRole(RoleCreateRequest req) {
        String code = req.code().toUpperCase(Locale.ROOT);
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getName, req.name())) > 0) {
            throw new BusinessException(ErrorCode.ROLE_NAME_EXISTS);
        }
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getCode, code)) > 0) {
            throw new BusinessException(ErrorCode.ROLE_CODE_EXISTS);
        }
        validatePermissions(req.permissions());

        Role role = new Role();
        role.setCode(code);
        role.setName(req.name());
        role.setDescription(req.description());
        role.setCreatedAt(LocalDateTime.now());
        roleMapper.insert(role);

        saveRolePermissions(role.getId(), req.permissions());
        return toVO(role);
    }

    /** 编辑自定义角色（仅描述 + 功能权限，名称/编码创建后不可改） */
    @Transactional
    public RoleVO updateRole(Long id, RoleUpdateRequest req) {
        Role role = requireCustomRole(id);
        validatePermissions(req.permissions());

        role.setDescription(req.description());
        roleMapper.updateById(role);

        saveRolePermissions(id, req.permissions());
        // PM-14：功能权限即时生效——刷新该角色下已登录用户的 Session 权限快照
        sessionPermissionRefresher.refreshRoleUsers(id);
        return toVO(roleMapper.selectById(id));
    }

    /** 删除自定义角色（有绑定用户则拒绝） */
    @Transactional
    public void deleteRole(Long id) {
        Role role = requireCustomRole(id);
        Long userCount = userMapper.selectUserCountByRoleId(id);
        if (userCount != null && userCount > 0) {
            throw new BusinessException(ErrorCode.ROLE_IN_USE,
                    "角色「" + role.getName() + "」仍被 " + userCount + " 个用户使用，请先调整这些用户的角色");
        }
        roleMapper.deleteById(id);
        rolePermissionMapper.deleteByRoleId(id);
        dataPermissionMapper.deleteByRoleId(id);
        // 删除后 result 无 name，故手动埋点记录角色名（对齐 SensitivityService.writeGeneralAudit 模式）
        writeRoleAudit(role, AuditOpType.DELETE);
    }

    /** 角色增删改通用审计（删除场景手动埋点；创建/编辑走 Controller 层 @AuditLog），fail-open */
    private void writeRoleAudit(Role role, AuditOpType opType) {
        try {
            auditLogRecorder.record(new AuditLogEvent(
                    currentUserId(), null,
                    opType.name(), AuditResourceType.ROLE.name(),
                    String.valueOf(role.getId()), role.getName(),
                    null, AuditLogEvent.RESULT_SUCCESS, null, null));
        } catch (Exception e) {
            // fail-open：审计失败不影响角色删除主链路
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** 查询角色（含权限点），供数据权限页角色选择器复用 */
    public Role getRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    private Role requireCustomRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        if (BUILTIN_ROLE_CODES.contains(role.getCode())) {
            throw new BusinessException(ErrorCode.BUILTIN_ROLE_READONLY);
        }
        return role;
    }

    /** 校验权限点 code 全部存在（并去重） */
    private void validatePermissions(List<String> codes) {
        List<String> distinct = codes.stream().distinct().toList();
        Long count = permissionMapper.selectCount(
                new LambdaQueryWrapper<Permission>().in(Permission::getCode, distinct));
        if (count == null || count != distinct.size()) {
            throw new BusinessException(ErrorCode.PERMISSION_CODE_INVALID);
        }
    }

    /** 全量重建角色权限点关联 */
    private void saveRolePermissions(Long roleId, List<String> codes) {
        rolePermissionMapper.deleteByRoleId(roleId);
        List<RolePermission> links = new ArrayList<>();
        for (String code : codes.stream().distinct().toList()) {
            Permission p = permissionMapper.selectOne(
                    new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(p.getId());
            rp.setCreatedAt(LocalDateTime.now());
            links.add(rp);
        }
        Db.saveBatch(links);
    }

    private RoleVO toVO(Role role) {
        List<String> permissions = rolePermissionMapper.selectCodesByRoleId(role.getId());
        return new RoleVO(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                BUILTIN_ROLE_CODES.contains(role.getCode()), permissions,
                role.getDataScope() == null ? DataScope.FULL : role.getDataScope(), role.getCreatedAt());
    }

    /** 查询角色成员（成员 Tab 已选列表） */
    public List<UserOptionDTO> listRoleUsers(Long roleId) {
        getRole(roleId);
        return userMapper.selectUsersByRoleId(roleId).stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getEmail()))
                .toList();
    }

    /** 设置角色成员（全量替换该角色的 user_role 关联，不影响用户其他角色） */
    @Transactional
    public void setRoleUsers(Long roleId, List<Long> userIds) {
        Role role = getRole(roleId);
        // 变更前取旧成员，刷新 Session 时新旧成员都要覆盖（被移出的用户权限也即时变化）
        List<Long> oldUserIds = userMapper.selectUsersByRoleId(roleId).stream().map(User::getId).toList();
        userMapper.deleteUserRolesByRoleId(roleId);
        List<Long> distinct = userIds.stream().distinct().toList();
        if (!distinct.isEmpty()) {
            // 批量校验用户存在，避免逐条 selectById（N+1）
            List<User> users = userMapper.selectBatchIds(distinct);
            if (users.size() != distinct.size()) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "存在无效的用户 ID");
            }
        }
        for (Long uid : distinct) {
            userMapper.insertUserRole((Long) idGenerator.nextId(null).longValue(), uid, roleId);
        }
        writeRoleAudit(role, AuditOpType.UPDATE);
        // PM-14：成员变更即时生效——刷新旧成员 + 新成员已登录用户的 Session 权限快照
        for (Long uid : distinct) {
            sessionPermissionRefresher.refreshUser(uid);
        }
        for (Long uid : oldUserIds) {
            sessionPermissionRefresher.refreshUser(uid);
        }
    }
}
