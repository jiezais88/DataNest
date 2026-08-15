package com.datanest.common.auth;

import com.datanest.common.model.DataPermissionGrant;
import com.datanest.common.model.UserDataPermissionDTO;

import java.util.Objects;

/**
 * 数据权限匹配工具（Sprint 11 F2，技术文档 D-2）。
 * <p>
 * 语义：{@code unrestricted=true} 全量放行；否则白名单最细粒度优先匹配。
 * 供 engineering（同步数据源选择）/ governance（资产目录、元数据树）/ data-service（SQL 终端、API 选表）
 * 经 system-api Feign 拉到 {@link UserDataPermissionDTO} 后本地过滤时复用，避免各服务重复实现。
 */
public final class DataPermissionMatcher {

    private DataPermissionMatcher() {
    }

    /** 数据源级判断：是否可访问该数据源（用于数据源下拉过滤） */
    public static boolean canAccessDatasource(UserDataPermissionDTO perm, Long datasourceId) {
        if (perm == null || perm.unrestricted()) {
            return true;
        }
        if (datasourceId == null) {
            return false;
        }
        return perm.grants().stream().anyMatch(g -> Objects.equals(g.datasourceId(), datasourceId));
    }

    /** 库级判断：是否可访问某数据源下的某库（数据源级或库级授权命中即放行） */
    public static boolean canAccessDatabase(UserDataPermissionDTO perm, Long datasourceId, String database) {
        if (perm == null || perm.unrestricted()) {
            return true;
        }
        if (datasourceId == null) {
            return false;
        }
        for (DataPermissionGrant g : perm.grants()) {
            if (!Objects.equals(g.datasourceId(), datasourceId)) {
                continue;
            }
            if (g.databaseName() == null) {
                // 数据源级：该数据源下所有库放行
                return true;
            }
            if (Objects.equals(g.databaseName(), database)) {
                return true;
            }
        }
        return false;
    }

    /** 表级判断：是否可访问某数据源下的某库某表（最细粒度优先匹配） */
    public static boolean canAccessTable(UserDataPermissionDTO perm, Long datasourceId, String database, String table) {
        if (perm == null || perm.unrestricted()) {
            return true;
        }
        if (datasourceId == null) {
            return false;
        }
        for (DataPermissionGrant g : perm.grants()) {
            if (!Objects.equals(g.datasourceId(), datasourceId)) {
                continue;
            }
            if (g.tableName() != null) {
                // 表级：精确匹配 库+表
                if (Objects.equals(g.tableName(), table) && Objects.equals(g.databaseName(), database)) {
                    return true;
                }
            } else if (g.databaseName() != null) {
                // 库级：匹配库
                if (Objects.equals(g.databaseName(), database)) {
                    return true;
                }
            } else {
                // 数据源级：全量放行该数据源
                return true;
            }
        }
        return false;
    }
}
