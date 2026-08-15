package com.datanest.system.api;

import com.datanest.common.model.Result;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.system.api.fallback.SystemPermissionApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 系统服务权限查询内部 Feign 契约（Sprint 11 F2）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-system 的 /system/internal/permissions 与
 * /system/internal/data-permission 端点；由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@FeignClient(name = "data-nest-system", path = "/system/internal", contextId = "systemPermissionApi",
        fallbackFactory = SystemPermissionApiFallbackFactory.class)
public interface SystemPermissionApi {

    /** 查询用户全部角色的权限点 code 并集 */
    @GetMapping("/permissions/{userId}")
    Result<List<String>> permissions(@PathVariable("userId") Long userId);

    /** 查询用户全部角色合并后的数据权限范围（三级白名单） */
    @GetMapping("/data-permission/{userId}")
    Result<UserDataPermissionDTO> dataPermission(@PathVariable("userId") Long userId);
}
