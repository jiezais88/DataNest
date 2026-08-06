package com.datanest.system.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SystemUserApi 熔断降级工厂。
 * <p>
 * 用户信息查询整体 fail-open：邮箱/用户名映射/用户 ID 查询均降级为空集，
 * 名称列、收件人等退化为空，不阻断主接口。
 */
@Component
public class SystemUserApiFallbackFactory implements FallbackFactory<SystemUserApi> {

    private static final Logger logger = LoggerFactory.getLogger(SystemUserApiFallbackFactory.class);

    @Override
    public SystemUserApi create(Throwable cause) {
        logger.warn("SystemUserApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new SystemUserApi() {
            @Override
            public Result<List<String>> emails(List<Long> ids) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Map<Long, String>> usernames(List<Long> ids) {
                return Result.ok(Map.of());
            }

            @Override
            public Result<List<Long>> findUserIdsByNameKeyword(String keyword) {
                return Result.ok(List.of());
            }
        };
    }
}
