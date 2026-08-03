package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.system.dto.UserOptionDTO;
import com.datanest.system.mapper.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户选择器接口（Sprint 5 告警中心接收人选择）。
 * 只返回已填写邮箱的平台用户，保证告警规则有效（ADR-S5-003）。
 */
@RestController
@RequestMapping("/users")
public class UserSelectorController {

    private final UserMapper userMapper;

    public UserSelectorController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/with-email")
    public Result<List<UserOptionDTO>> listUsersWithEmail(@RequestParam(required = false) String keyword) {
        List<UserOptionDTO> options = userMapper.selectUsersWithEmail(keyword).stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getEmail()))
                .toList();
        return Result.ok(options);
    }
}
