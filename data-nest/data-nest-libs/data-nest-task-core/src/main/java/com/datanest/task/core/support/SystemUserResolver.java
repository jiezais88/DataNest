package com.datanest.task.core.support;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;

import java.util.Collection;
import java.util.Map;

/**
 * 用户 ID 批量反查用户名工具（2026-08-12 下沉）。
 * <p>
 * 收敛来源：engineering（SyncJobService/DataSourceService/DagService/DagVersionService/
 * DagProjectService/TaskTemplateService）与 governance（QualityRuleService/QualityJobService/
 * NamingStandardService/FieldTypeStandardService/CollectTaskService/MetadataService 等）各自
 * 逐字复制了「过滤空 ids → Feign 批量 usernames → 失败降级空 Map」的方法体，统一委托此处。
 * <p>
 * 用法：服务注入 {@link SystemUserApi} 后，把 {@link #usernames(SystemUserApi, Collection)}
 * 作为共享查询；远端失败降级空 Map（不阻断业务）。realtime/alert 因不依赖 task-core 保留本地实现。
 */
public final class SystemUserResolver {

    private SystemUserResolver() {
    }

    /**
     * 批量反查用户名。空集合直接返回空 Map；Feign 调用失败降级空 Map（读路径不阻断）。
     */
    public static Map<Long, String> usernames(SystemUserApi systemUserApi, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        }, Map.of());
    }
}
