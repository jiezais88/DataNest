package com.datanest.dataservice.api;

import com.datanest.common.model.Result;
import com.datanest.dataservice.api.dto.CleanupRequest;
import com.datanest.dataservice.api.fallback.DataServiceOpsApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 数据服务低频运维内部 Feign 契约（Sprint 10 F1）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-data-service 的 /data-service/internal/** 端点。
 * 定时清理统一由 app-job 调度（本地禁止 @Scheduled，见 docs/agent/conventions-backend.md），
 * 本契约供 job 的 SQL 查询历史清理 handler 触发。
 */
@FeignClient(name = "data-nest-data-service", path = "/data-service/internal", contextId = "dataServiceOpsApi",
        fallbackFactory = DataServiceOpsApiFallbackFactory.class)
public interface DataServiceOpsApi {

    /** 清理超过保留天数的 SQL 查询历史（sql_query_history），返回删除条数 */
    @PostMapping("/sql-history/cleanup")
    Result<Integer> cleanupSqlQueryHistory(@RequestBody CleanupRequest request);
}
