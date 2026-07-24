package com.datanest.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.dto.*;
import com.datanest.system.entity.Role;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.RoleMapper;
import com.datanest.system.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdentifierGenerator idGenerator;

    public UserService(UserMapper userMapper, RoleMapper roleMapper,
                       PasswordEncoder passwordEncoder, IdentifierGenerator idGenerator) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
    }

    public UserLoginDTO verify(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        log.info("Login attempt: username={}, inputPassword={}, dbPassword={}, match={}",
                username, password, user.getPassword(),
                passwordEncoder.matches(password, user.getPassword()));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }
        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return new UserLoginDTO(user.getId(), user.getUsername(),
                user.getPassword(), user.getEnabled(), roles, List.of());
    }

    public PageResult<UserVO> listUsers(int page, int pageSize, String keyword, String roleCode, String status) {
        Boolean enabled = null;
        if (status != null && !status.isEmpty()) {
            enabled = "enabled".equals(status);
        }

        Page<User> mpPage = new Page<>(page, pageSize);
        IPage<User> result = userMapper.selectUserPage(mpPage, keyword, roleCode, enabled);

        List<UserVO> records = result.getRecords().stream().map(u -> {
            List<String> roles = userMapper.selectRoleCodesByUserId(u.getId());
            return new UserVO(u.getId(), u.getUsername(), u.getEmail(),
                    u.getPhone(), u.getEnabled(), roles, u.getCreatedAt(), u.getUpdatedAt());
        }).toList();

        return PageResult.of(records, result.getTotal(), page, pageSize);
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
        userMapper.insert(user);

        assignRoles(user.getId(), req.roles());

        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), true, roles, user.getCreatedAt(), user.getUpdatedAt());
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
        userMapper.updateById(user);

        if (req.roles() != null) {
            assignRoles(userId, req.roles());
        }

        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return new UserVO(user.getId(), user.getUsername(), user.getEmail(),
                user.getPhone(), user.getEnabled(), roles, user.getCreatedAt(), user.getUpdatedAt());
    }

    public void toggleStatus(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        user.setEnabled(!user.getEnabled());
        userMapper.updateById(user);
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
    }
}
