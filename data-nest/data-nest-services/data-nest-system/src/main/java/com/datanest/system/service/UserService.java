package com.datanest.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.dto.UserCreateRequest;
import com.datanest.system.dto.UserUpdateRequest;
import com.datanest.system.dto.UserVO;
import com.datanest.system.entity.Role;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.PermissionMapper;
import com.datanest.system.mapper.RoleMapper;
import com.datanest.system.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdentifierGenerator idGenerator;
    private final SysUserService sysUserService;
    private final SessionPermissionRefresher sessionPermissionRefresher;

    public UserService(UserMapper userMapper, RoleMapper roleMapper, PermissionMapper permissionMapper,
                       PasswordEncoder passwordEncoder, IdentifierGenerator idGenerator,
                       SysUserService sysUserService, SessionPermissionRefresher sessionPermissionRefresher) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.sysUserService = sysUserService;
        this.sessionPermissionRefresher = sessionPermissionRefresher;
    }

    /**
     * 当前登录用户最新信息（PM-14：进入应用时刷新权限快照，无需重新登录）。
     * 与 verify 的 roles/permissions 计算一致，供前端 /auth/me 返回最新权限。
     */
    public UserLoginDTO getCurrentUserInfo() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = permissionMapper.selectCodesByUserId(userId);
        return new UserLoginDTO(user.getId(), user.getUsername(),
                user.getPassword(), user.getEnabled(), roles, permissions);
    }

    public UserLoginDTO verify(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }
        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = permissionMapper.selectCodesByUserId(user.getId());
        return new UserLoginDTO(user.getId(), user.getUsername(),
                user.getPassword(), user.getEnabled(), roles, permissions);
    }

    public PageResult<UserVO> listUsers(int page, int pageSize, String keyword, String roleCode, String status) {
        Boolean enabled = null;
        if (status != null && !status.isEmpty()) {
            enabled = "enabled".equals(status);
        }

        Page<User> mpPage = new Page<>(page, pageSize);
        IPage<User> result = userMapper.selectUserPage(mpPage, keyword, roleCode, enabled);

        List<User> records = result.getRecords();
        Set<Long> userIds = records.stream()
                .flatMap(u -> java.util.stream.Stream.of(u.getCreatedBy(), u.getUpdatedBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> usernameMap = sysUserService.getUsernameMap(userIds);

        List<UserVO> vos = records.stream().map(u -> {
            List<String> roles = userMapper.selectRoleCodesByUserId(u.getId());
            return new UserVO(u.getId(), u.getUsername(), u.getEmail(),
                    u.getPhone(), u.getEnabled(), roles, u.getCreatedAt(), u.getUpdatedAt(),
                    u.getCreatedBy(), usernameMap.getOrDefault(u.getCreatedBy(), "-"),
                    u.getUpdatedBy(), usernameMap.getOrDefault(u.getUpdatedBy(), "-"));
        }).toList();

        return PageResult.of(vos, result.getTotal(), page, pageSize);
    }

    @Transactional
    public UserVO createUser(UserCreateRequest req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setEmail(req.email());
        user.setPhone(req.phone());
        user.setEnabled(true);
        Long operatorId = currentUserId();
        user.setCreatedBy(operatorId);
        userMapper.insert(user);

        assignRoles(user.getId(), req.roles());

        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return toUserVO(user, roles);
    }

    @Transactional
    public UserVO updateUser(Long userId, UserUpdateRequest req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (req.email() != null) user.setEmail(req.email());
        if (req.phone() != null) user.setPhone(req.phone());
        if (req.password() != null && !req.password().isEmpty()) {
            user.setPassword(passwordEncoder.encode(req.password()));
        }
        user.setUpdatedBy(currentUserId());
        userMapper.updateById(user);

        if (req.roles() != null) {
            assignRoles(userId, req.roles());
        }

        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return toUserVO(user, roles);
    }

    private UserVO toUserVO(User user, List<String> roles) {
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), user.getEnabled(), roles, user.getCreatedAt(), user.getUpdatedAt(),
                user.getCreatedBy(), "-", user.getUpdatedBy(), "-");
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    public void toggleStatus(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setEnabled(!user.getEnabled());
        userMapper.updateById(user);
        // 禁用用户后强制下线其当前会话
        if (!user.getEnabled()) {
            StpUtil.logout(userId);
        }
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_ERROR);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    public void resetPassword(Long userId, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    private void assignRoles(Long userId, List<String> roleCodes) {
        userMapper.deleteUserRoles(userId);
        for (String code : roleCodes) {
            Role role = roleMapper.selectOne(
                    new LambdaQueryWrapper<Role>().eq(Role::getCode, code));
            if (role == null) {
                throw new BusinessException(ErrorCode.INVALID_ROLE, "角色不存在: " + code);
            }
            userMapper.insertUserRole((Long) idGenerator.nextId(null).longValue(), userId, role.getId());
        }
        // PM-14：用户角色变更即时生效——刷新该用户已登录 Session 的权限快照
        sessionPermissionRefresher.refreshUser(userId);
    }
}
