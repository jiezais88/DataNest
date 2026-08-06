package com.datanest.system.api;

import com.datanest.common.model.Result;
import com.datanest.system.api.fallback.SystemUserApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 系统服务用户内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-system 的 /system/internal/users/** 端点。
 */
@FeignClient(name = "data-nest-system", path = "/system/internal/users", contextId = "systemUserApi",
        fallbackFactory = SystemUserApiFallbackFactory.class)
public interface SystemUserApi {

    /** 按用户 ID 列表查询邮箱 */
    @GetMapping("/emails")
    Result<List<String>> emails(@RequestParam("ids") List<Long> ids);

    /** 按用户 ID 列表查询用户名映射 */
    @GetMapping("/usernames")
    Result<Map<Long, String>> usernames(@RequestParam("ids") List<Long> ids);

    /** 按用户名模糊查询 userId 列表（资产搜索「负责人」维度） */
    @GetMapping("/ids-by-name-keyword")
    Result<List<Long>> findUserIdsByNameKeyword(@RequestParam("keyword") String keyword);
}
