package com.datanest.system.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.config.SsoProperties;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 密码策略（Sprint 14，仅 LOCAL 用户）：
 * 复杂度校验 / 登录失败锁定 / 密码过期。
 * <p>
 * 复杂度校验作用于所有 LOCAL 密码写入点（创建、编辑改密、重置、自主修改）；
 * 失败锁定与过期作用于本地密码登录链路。SSO 用户不校验密码策略。
 */
@Service
public class PasswordPolicyService {

    private final SsoConfigService ssoConfigService;
    private final UserMapper userMapper;

    public PasswordPolicyService(SsoConfigService ssoConfigService, UserMapper userMapper) {
        this.ssoConfigService = ssoConfigService;
        this.userMapper = userMapper;
    }

    /** 复杂度校验：不满足抛 PASSWORD_POLICY_VIOLATION */
    public void validate(String password) {
        SsoProperties.PasswordPolicy p = policy();
        if (p == null) {
            return;
        }
        if (password == null || password.length() < p.getMinLength()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
        if (p.isRequireUppercase() && !contains(password, true, false)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
        if (p.isRequireLowercase() && !contains(password, false, true)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
        if (p.isRequireDigit() && password.chars().noneMatch(Character::isDigit)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
        if (p.isRequireSpecial() && password.chars().allMatch(ch -> Character.isLetterOrDigit(ch))) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }
    }

    private boolean contains(String password, boolean requireUpper, boolean requireLower) {
        if (requireUpper) {
            return password.chars().anyMatch(ch -> Character.isUpperCase(ch));
        }
        if (requireLower) {
            return password.chars().anyMatch(ch -> Character.isLowerCase(ch));
        }
        return true;
    }

    /** 登录成功：清零失败计数与锁定（显式 set null，updateById 忽略 null 字段） */
    public void recordLoginSuccess(User user) {
        boolean dirty = (user.getLoginFailCount() != null && user.getLoginFailCount() > 0)
                || user.getLockedUntil() != null;
        if (dirty) {
            resetLoginState(user.getId());
        }
    }

    /** 清零失败计数与锁定截止（用于解锁/改密/登录成功，LambdaUpdateWrapper 显式 set null） */
    public void resetLoginState(Long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getLoginFailCount, 0)
                .set(User::getLockedUntil, null));
    }

    /** 登录失败：累加计数，达到阈值锁定 lock-minutes 分钟 */
    public void recordLoginFailure(User user) {
        SsoProperties.PasswordPolicy p = policy();
        int failMax = p != null ? Math.max(1, p.getFailMax()) : 5;
        int lockMinutes = p != null ? Math.max(1, p.getLockMinutes()) : 30;
        int count = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        if (count >= failMax) {
            user.setLoginFailCount(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
        } else {
            user.setLoginFailCount(count);
        }
        userMapper.updateById(user);
    }

    /** 当前是否处于锁定状态（已过锁定期自动视为未锁定，解锁时机在登录校验） */
    public boolean isLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
    }

    /** 设置/刷新密码过期时间（expire-days<=0 不过期） */
    public void applyExpiry(User user) {
        user.setPasswordExpireAt(nextExpiryAt());
    }

    /** 按策略计算密码过期时间（expire-days<=0 返回 null），供 Wrapper 更新使用 */
    public LocalDateTime nextExpiryAt() {
        SsoProperties.PasswordPolicy p = policy();
        if (p != null && p.getExpireDays() > 0) {
            return LocalDateTime.now().plusDays(p.getExpireDays());
        }
        return null;
    }

    /** 密码是否已过期（LOCAL 用户且配置了过期） */
    public boolean isExpired(User user) {
        if (!"LOCAL".equals(user.getAuthSource())) {
            return false;
        }
        return user.getPasswordExpireAt() != null
                && user.getPasswordExpireAt().isBefore(LocalDateTime.now());
    }

    private SsoProperties.PasswordPolicy policy() {
        return ssoConfigService.getSsoProperties().getPasswordPolicy();
    }
}
