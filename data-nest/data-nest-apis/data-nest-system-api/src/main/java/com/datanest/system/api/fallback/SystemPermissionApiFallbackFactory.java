package com.datanest.system.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.system.api.SystemPermissionApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SystemPermissionApi 熔断降级工厂（Sprint 11 F2）。
 * <p>
 * 权限查询整体 fail-closed（安全默认）：
 * - 权限点查询失败 → 返回空集合（不授予任何权限点，写接口 403）；
 * - 数据权限查询失败 → 返回 {@code unrestricted=false} + 空白名单（拒绝数据访问）。
 * system 短暂故障时宁可收紧权限也不泄露数据（对齐 PRD「分析师/治理管理员/自定义角色默认无数据访问」安全默认）。
 */
@Component
public class SystemPermissionApiFallbackFactory implements FallbackFactory<SystemPermissionApi> {

    private static final Logger logger = LoggerFactory.getLogger(SystemPermissionApiFallbackFactory.class);

    @Override
    public SystemPermissionApi create(Throwable cause) {
        logger.warn("SystemPermissionApi 权限查询降级（fail-closed）: {}", cause == null ? "unknown" : cause.getMessage());
        return new SystemPermissionApi() {
            @Override
            public Result<List<String>> permissions(Long userId) {
                return Result.ok(List.of());
            }

            @Override
            public Result<UserDataPermissionDTO> dataPermission(Long userId) {
                return Result.ok(new UserDataPermissionDTO(false, List.of()));
            }
        };
    }
}
