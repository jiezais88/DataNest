package com.datanest.task.core.support;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.system.api.SystemPermissionApi;

/**
 * 用户数据权限范围解析工具（2026-08-15 下沉，Sprint 11 F2 review 收敛）。
 * <p>
 * 收敛来源：engineering（DataSourceService）、governance（MetadataService/AssetCatalogService）、
 * data-service（SqlQueryService）各自逐字复制了「StpUtil 取登录态 → Feign 查 dataPermission →
 * fail-open 返回全量 / fail-closed 抛 2013」的方法体，统一委托此处。
 * <p>
 * 策略语义（消费方按入口自行选择）：
 * <ul>
 *   <li>{@link #resolveFailOpen(SystemPermissionApi)} —— 展示层过滤用（元数据树/库表浏览/下拉），
 *       权限服务不可用时降级全量放行，仅减少暴露，不作为安全边界；</li>
 *   <li>{@link #resolveFailClosed(SystemPermissionApi)} 及带 userId 重载 —— 取数/写操作前校验用
 *       （SQL 执行/API 提交/同步创建），权限服务不可用时抛
 *       {@link ErrorCode#DATA_PERMISSION_SERVICE_UNAVAILABLE} 拒绝（安全默认）。</li>
 * </ul>
 * 内置 Doris（datasourceId=-1）恒全量放行，由消费方在匹配前自行判断。
 * 无登录态（内部定时任务等）返回全量放行。
 */
public final class DataPermissionResolver {

    private DataPermissionResolver() {
    }

    /**
     * 解析当前登录用户数据权限（fail-open：权限服务不可用/无登录态返回全量，用于展示层过滤）。
     */
    public static UserDataPermissionDTO resolveFailOpen(SystemPermissionApi systemPermissionApi) {
        Long userId = currentUserId();
        if (userId == null) {
            return UserDataPermissionDTO.fullAccess();
        }
        return doResolve(systemPermissionApi, userId, UserDataPermissionDTO.fullAccess());
    }

    /**
     * 解析当前登录用户数据权限（fail-closed：权限服务不可用抛 2013，用于取数/写操作前安全校验）。
     */
    public static UserDataPermissionDTO resolveFailClosed(SystemPermissionApi systemPermissionApi) {
        Long userId = currentUserId();
        if (userId == null) {
            return UserDataPermissionDTO.fullAccess(); // 内部场景无登录态不校验
        }
        return doResolve(systemPermissionApi, userId, null);
    }

    /**
     * 解析指定用户数据权限（fail-closed：权限服务不可用抛 2013）。userId 为 null 返回全量放行。
     */
    public static UserDataPermissionDTO resolveFailClosed(SystemPermissionApi systemPermissionApi, Long userId) {
        if (userId == null) {
            return UserDataPermissionDTO.fullAccess(); // 内部场景无登录态不校验
        }
        return doResolve(systemPermissionApi, userId, null);
    }

    /**
     * 统一查询入口。fallback 语义：null = fail-closed（抛 2013）；其它值 = fail-open 降级值。
     */
    private static UserDataPermissionDTO doResolve(SystemPermissionApi api, Long userId,
                                                   UserDataPermissionDTO fallback) {
        UserDataPermissionDTO perm = RemoteCalls.execute("system.data-permission", () -> {
            Result<UserDataPermissionDTO> result = api.dataPermission(userId);
            return result == null || result.data() == null ? null : result.data();
        }, null);
        if (perm == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new BusinessException(ErrorCode.DATA_PERMISSION_SERVICE_UNAVAILABLE);
        }
        return perm;
    }

    /** 当前登录用户 ID；无登录态返回 null（内部任务等场景） */
    private static Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }
}
