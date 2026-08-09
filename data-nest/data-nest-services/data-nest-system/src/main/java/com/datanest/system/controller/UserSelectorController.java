package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.system.dto.UserOptionDTO;
import com.datanest.system.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户选择器接口（Sprint 5 告警中心接收人选择）。
 * 只返回已填写邮箱的平台用户，保证告警规则有效（ADR-S5-003）。
 */
@Tag(name = "用户选项", description = "用户选择器轻量选项（告警接收人 / 资产负责人）")
@RestController
@RequestMapping("/users")
public class UserSelectorController {

    private final UserMapper userMapper;

    public UserSelectorController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Operation(summary = "已填写邮箱的用户选项", description = "告警中心接收人选择用，只返回有邮箱的用户")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/with-email")
    public Result<List<UserOptionDTO>> listUsersWithEmail(
            @Parameter(description = "用户名关键字") @RequestParam(required = false) String keyword) {
        List<UserOptionDTO> options = userMapper.selectUsersWithEmail(keyword).stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getEmail()))
                .toList();
        return Result.ok(options);
    }

    @Operation(summary = "全部启用用户选项", description = "资产负责人选择器用，不要求填写邮箱")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/options")
    public Result<List<UserOptionDTO>> listUserOptions(
            @Parameter(description = "用户名关键字") @RequestParam(required = false) String keyword) {
        List<UserOptionDTO> options = userMapper.selectEnabledUserOptions(keyword).stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getEmail()))
                .toList();
        return Result.ok(options);
    }
}
