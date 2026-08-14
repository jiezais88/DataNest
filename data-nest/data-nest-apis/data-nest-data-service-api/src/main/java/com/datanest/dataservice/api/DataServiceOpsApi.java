package com.datanest.dataservice.api;

import com.datanest.common.model.Result;
import com.datanest.dataservice.api.dto.CleanupRequest;
import com.datanest.dataservice.api.dto.DisableApisByTableRequest;
import com.datanest.dataservice.api.fallback.DataServiceOpsApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 数据服务低频运维内部 Feign 契约（Sprint 10 F1/F3/F4）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-data-service 的 /data-service/internal/** 端点。
 * 定时清理统一由 app-job 调度（本地禁止 @Scheduled，见 docs/agent/conventions-backend.md），
 * 本契约供 job 的清理 handler 触发；管道删除联动由 realtime 触发。
 */
@FeignClient(name = "data-nest-data-service", path = "/data-service/internal", contextId = "dataServiceOpsApi",
        fallbackFactory = DataServiceOpsApiFallbackFactory.class)
public interface DataServiceOpsApi {

    /** 清理超过保留天数的 SQL 查询历史（sql_query_history），返回删除条数 */
    @PostMapping("/sql-history/cleanup")
    Result<Integer> cleanupSqlQueryHistory(@RequestBody CleanupRequest request);

    /** 将指定元数据表的所有已发布 API 强制下线；机密改级联动；返回下线 API 数 */
    @PostMapping("/api-disable-by-tables")
    Result<Integer> disableApisByMetadataTableIds(@RequestBody DisableApisByTableRequest request);

    /** 清理超过保留天数的 API 调用明细（api_call_log），返回删除条数 */
    @PostMapping("/api-call-log/cleanup")
    Result<Integer> cleanupApiCallLog(@RequestBody CleanupRequest request);

    /** CDC 管道删除时解绑所有 Key 的管道授权（api_key_pipeline），返回解绑条数 */
    @PostMapping("/pipelines/{pipelineId}/unbind-keys")
    Result<Integer> unbindKeysByPipeline(@PathVariable("pipelineId") Long pipelineId);
}
