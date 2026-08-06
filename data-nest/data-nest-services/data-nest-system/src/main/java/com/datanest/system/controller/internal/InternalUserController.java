package com.datanest.system.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.task.core.entity.SysUser;
import com.datanest.task.core.mapper.SysUserMapper;
import com.datanest.task.core.service.SysUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户内部接口（实现 system-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用（如告警服务反查收件人邮箱/用户名），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final SysUserMapper sysUserMapper;
    private final SysUserService sysUserService;

    public InternalUserController(SysUserMapper sysUserMapper, SysUserService sysUserService) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserService = sysUserService;
    }

    /** 按用户 ID 列表查询邮箱（仅返回已填写邮箱的用户） */
    @GetMapping("/emails")
    public Result<List<String>> emails(@RequestParam List<Long> ids) {
        List<String> emails = sysUserMapper.selectEmailsByIds(ids).stream()
                .map(SysUser::getEmail)
                .toList();
        return Result.ok(emails);
    }

    /** 按用户 ID 列表查询 userId → username 映射 */
    @GetMapping("/usernames")
    public Result<Map<Long, String>> usernames(@RequestParam List<Long> ids) {
        return Result.ok(sysUserService.getUsernameMap(ids));
    }

    /** 按用户名模糊查询 userId 列表（资产搜索「负责人」维度） */
    @GetMapping("/ids-by-name-keyword")
    public Result<List<Long>> findUserIdsByNameKeyword(@RequestParam String keyword) {
        return Result.ok(sysUserService.findUserIdsByNameKeyword(keyword));
    }
}
