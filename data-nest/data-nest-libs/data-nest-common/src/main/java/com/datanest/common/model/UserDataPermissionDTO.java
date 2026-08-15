package com.datanest.common.model;

import java.util.List;

/**
 * 用户数据权限范围（Sprint 11 F2，system internal 端点返回）。
 * <p>
 * {@code unrestricted=true} = 用户全部角色均无数据权限白名单记录 = 全量可见（默认，向后兼容）；
 * {@code unrestricted=false} = 存在白名单记录，仅 {@code grants} 覆盖的数据可访问（最细粒度优先）。
 * 匹配逻辑统一走 common {@code DataPermissionMatcher}，禁止各服务本地再造。
 */
public record UserDataPermissionDTO(
        boolean unrestricted,
        List<DataPermissionGrant> grants
) {

    public static UserDataPermissionDTO fullAccess() {
        return new UserDataPermissionDTO(true, List.of());
    }
}
