package com.datanest.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.dto.ProfileUpdateRequest;
import com.datanest.system.dto.UserCreateRequest;
import com.datanest.system.dto.UserProfileDTO;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final PasswordPolicyService passwordPolicyService;
    private final RoleMappingService roleMappingService;

    public UserService(UserMapper userMapper, RoleMapper roleMapper, PermissionMapper permissionMapper,
                       PasswordEncoder passwordEncoder, IdentifierGenerator idGenerator,
                       SysUserService sysUserService, SessionPermissionRefresher sessionPermissionRefresher,
                       PasswordPolicyService passwordPolicyService, RoleMappingService roleMappingService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.sysUserService = sysUserService;
        this.sessionPermissionRefresher = sessionPermissionRefresher;
        this.passwordPolicyService = passwordPolicyService;
        this.roleMappingService = roleMappingService;
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

    /**
     * 当前登录用户完整资料（个人中心）。
     * 比 getCurrentUserInfo 多返回 email/phone/createdAt，供 /auth/profile 展示身份信息。
     */
    public UserProfileDTO getCurrentUserProfile() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return new UserProfileDTO(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), roles, user.getCreatedAt());
    }

    /**
     * 个人中心：更新当前登录用户资料（仅邮箱/手机号）。
     * 字段为 null 表示不修改；空字符串/空白表示清空（存 null）。
     * 用 LambdaUpdateWrapper.set 显式置 null（updateById 的 NOT_NULL 策略不会更新 null 字段）。
     */
    @Transactional
    public void updateCurrentUserProfile(ProfileUpdateRequest req) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (req.email() == null && req.phone() == null) {
            return;
        }
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId);
        wrapper.set(User::getUpdatedBy, userId);
        wrapper.set(User::getUpdatedAt, LocalDateTime.now());
        if (req.email() != null) {
            wrapper.set(User::getEmail, req.email().isBlank() ? null : req.email().trim());
        }
        if (req.phone() != null) {
            wrapper.set(User::getPhone, req.phone().isBlank() ? null : req.phone().trim());
        }
        userMapper.update(null, wrapper);
    }

    /**
     * 本地密码登录（Sprint 14 升级版）：认证来源校验 + 失败锁定 + 密码过期检测。
     * 返回 LocalLoginResult（含 mustChangePwd），供 AuthController 登录。
     */
    public LocalLoginResult authenticateLocal(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getAuthSource() != null && !"LOCAL".equals(user.getAuthSource())) {
            // 企业身份账号不支持本地密码登录（PRD R1）
            throw new BusinessException(ErrorCode.LOCAL_AUTH_NOT_ALLOWED);
        }
        if (passwordPolicyService.isLocked(user)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            passwordPolicyService.recordLoginFailure(user);
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        passwordPolicyService.recordLoginSuccess(user);
        boolean mustChangePwd = passwordPolicyService.isExpired(user);
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = permissionMapper.selectCodesByUserId(user.getId());
        return new LocalLoginResult(user.getId(), user.getUsername(), roles, permissions, mustChangePwd);
    }

    /** 兼容入口：verify 仅供既有调用方使用，密码策略语义与 authenticateLocal 一致 */
    public UserLoginDTO verify(String username, String password) {
        LocalLoginResult r = authenticateLocal(username, password);
        return new UserLoginDTO(r.userId(), r.username(), null, true, r.roles(), r.permissions());
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
                    u.getPhone(), u.getEnabled(), u.getAuthSource(), u.getSsoSubject(), u.getLockedUntil(),
                    roles, u.getCreatedAt(), u.getUpdatedAt(),
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

        passwordPolicyService.validate(req.password());
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setEmail(req.email());
        user.setPhone(req.phone());
        user.setEnabled(true);
        user.setAuthSource("LOCAL");
        // Sprint 14：创建即计算密码过期时间（expire-days<=0 不过期）
        passwordPolicyService.applyExpiry(user);
        Long operatorId = currentUserId();
        user.setCreatedBy(operatorId);
        userMapper.insert(user);

        assignRoles(user.getId(), req.roles());

        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return toUserVO(user, roles);
    }

    @Transactional
    public UserVO updateUser(Long userId, UserUpdateRequest req) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 用 LambdaUpdateWrapper.set 显式置 null（updateById 的 NOT_NULL 策略不会更新 null 字段），
        // 空字符串/空白表示清空（存 null），null 表示不修改
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId);
        wrapper.set(User::getUpdatedBy, currentUserId());
        wrapper.set(User::getUpdatedAt, LocalDateTime.now());
        if (req.email() != null) {
            wrapper.set(User::getEmail, req.email().isBlank() ? null : req.email().trim());
        }
        if (req.phone() != null) {
            wrapper.set(User::getPhone, req.phone().isBlank() ? null : req.phone().trim());
        }
        if (req.password() != null && !req.password().isEmpty()) {
            passwordPolicyService.validate(req.password());
            wrapper.set(User::getPassword, passwordEncoder.encode(req.password()));
            // Sprint 14：改密后按策略重新计时 + 清零失败计数与锁定
            wrapper.set(User::getPasswordExpireAt, passwordPolicyService.nextExpiryAt());
            wrapper.set(User::getLoginFailCount, 0);
            wrapper.set(User::getLockedUntil, null);
        }
        userMapper.update(null, wrapper);

        if (req.roles() != null) {
            assignRoles(userId, req.roles());
        }

        User updated = userMapper.selectById(userId);
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return toUserVO(updated, roles);
    }

    private UserVO toUserVO(User user, List<String> roles) {
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), user.getEnabled(), user.getAuthSource(), user.getSsoSubject(), user.getLockedUntil(),
                roles, user.getCreatedAt(), user.getUpdatedAt(),
                user.getCreatedBy(), "-", user.getUpdatedBy(), "-");
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** 切换启用/禁用，返回切换后的用户信息（enabled 区分启用/禁用审计，username 供审计名） */
    public UserVO toggleStatus(Long userId) {
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
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return toUserVO(user, roles);
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getAuthSource() != null && !"LOCAL".equals(user.getAuthSource())) {
            throw new BusinessException(ErrorCode.LOCAL_AUTH_NOT_ALLOWED);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_ERROR);
        }
        passwordPolicyService.validate(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        // Sprint 14：改密后按策略重新计时 + 清零失败计数与锁定（显式 set null）
        passwordPolicyService.applyExpiry(user);
        userMapper.updateById(user);
        passwordPolicyService.resetLoginState(userId);
    }

    public void resetPassword(Long userId, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getAuthSource() != null && !"LOCAL".equals(user.getAuthSource())) {
            // 企业身份账号无本地密码，不支持重置（PRD R1）
            throw new BusinessException(ErrorCode.LOCAL_AUTH_NOT_ALLOWED);
        }
        passwordPolicyService.validate(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        // Sprint 14：重置后按策略重新计时 + 清零失败计数与锁定（解除锁定，显式 set null）
        passwordPolicyService.applyExpiry(user);
        userMapper.updateById(user);
        passwordPolicyService.resetLoginState(userId);
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

    /**
     * SSO/LDAP 登录用户解析（PRD R2 三分支）：
     * ① subject 命中 → 已绑定用户；
     * ② email / username 命中本地账号 → 自动绑定（该账号未被其他企业身份占用时）；
     * ③ 均未命中 → 自动建号（随机密码不可本地登录 + 默认角色）。
     * D4 角色映射：命中规则则覆盖角色，未命中保留管理员手动调整。
     */
    @Transactional
    public User resolveSsoUser(String source, String subject, String email, String username, List<String> groups) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getSsoSubject, subject));
        if (user != null) {
            applyRoleMappingIfHit(user.getId(), groups);
            return user;
        }
        if (email != null && !email.isBlank()) {
            // email 无唯一约束，取首条（避免多条匹配抛异常）
            List<User> emailMatches = userMapper.selectList(
                    new LambdaQueryWrapper<User>().eq(User::getEmail, email).last("LIMIT 1"));
            if (!emailMatches.isEmpty()) {
                user = emailMatches.get(0);
            }
        }
        if (user == null && username != null && !username.isBlank()) {
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        }
        if (user != null) {
            if (user.getSsoSubject() != null && !user.getSsoSubject().equals(subject)) {
                // 该邮箱/用户名已被其他企业身份绑定，禁止抢注（PRD R2）
                throw new BusinessException(ErrorCode.SSO_BINDING_CONFLICT);
            }
            user.setAuthSource(source);
            user.setSsoSubject(subject);
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            applyRoleMappingIfHit(user.getId(), groups);
            return user;
        }
        // 自动建号
        user = new User();
        user.setUsername(resolveUniqueUsername(username, email, subject));
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 随机密码，不可本地登录
        user.setEmail(email);
        user.setEnabled(true);
        user.setAuthSource(source);
        user.setSsoSubject(subject);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        List<String> mapped = roleMappingService.resolveRoles(groups);
        List<String> roles = mapped.isEmpty()
                ? List.of(roleMappingService.defaultRole())
                : mapped;
        assignRoles(user.getId(), roles);
        return user;
    }

    /** D4：命中映射规则才覆盖角色（未命中保留管理员手动调整） */
    private void applyRoleMappingIfHit(Long userId, List<String> groups) {
        List<String> mapped = roleMappingService.resolveRoles(groups);
        if (!mapped.isEmpty()) {
            assignRoles(userId, mapped);
        }
    }

    /** 自动建号用户名：优先 IdP 用户名，其次邮箱前缀，冲突时加随机后缀 */
    private String resolveUniqueUsername(String username, String email, String subject) {
        String candidate = username;
        if (candidate == null || candidate.isBlank()) {
            if (email != null && !email.isBlank()) {
                candidate = email.split("@")[0];
            } else {
                candidate = "sso_" + UUID.randomUUID().toString().substring(0, 8);
            }
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, candidate)) == 0) {
            return candidate;
        }
        return candidate + "_" + UUID.randomUUID().toString().substring(0, 4);
    }

    /** 按 sso_subject 判断用户是否已存在（同步统计用） */
    public boolean existsBySsoSubject(String subject) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getSsoSubject, subject)) > 0;
    }

    /** 解绑企业身份（PRD F6）：恢复 LOCAL，清除 sso_subject（幂等），返回更新后的用户信息 */
    @Transactional
    public UserVO unbindSso(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getSsoSubject() == null) {
            return toUserVO(user, userMapper.selectRoleCodesByUserId(userId)); // 幂等返回
        }
        user.setSsoSubject(null);
        user.setAuthSource("LOCAL");
        user.setUpdatedBy(currentUserId());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        sessionPermissionRefresher.refreshUser(userId);
        return toUserVO(user, userMapper.selectRoleCodesByUserId(userId));
    }

    /** 解除登录锁定（PRD F6）：清零失败计数与锁定截止（LambdaUpdateWrapper 显式 set null，避免 updateById 忽略 null 或写回旧值） */
    @Transactional
    public UserVO unlockUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getLoginFailCount, 0)
                .set(User::getLockedUntil, null)
                .set(User::getUpdatedBy, currentUserId())
                .set(User::getUpdatedAt, LocalDateTime.now()));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        return toUserVO(user, userMapper.selectRoleCodesByUserId(userId));
    }

    /** 本地密码登录结果（含强制改密标记） */
    public record LocalLoginResult(Long userId, String username,
                                   List<String> roles, List<String> permissions,
                                   boolean mustChangePwd) {
    }
}
