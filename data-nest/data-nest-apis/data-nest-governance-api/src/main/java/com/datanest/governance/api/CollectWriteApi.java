package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.CollectChangeDetailBatchRequest;
import com.datanest.governance.api.dto.CollectDetectDeletedTablesRequest;
import com.datanest.governance.api.dto.CollectHistoryCreateRequest;
import com.datanest.governance.api.dto.CollectHistoryFinishRequest;
import com.datanest.governance.api.dto.CollectHistoryInfoDTO;
import com.datanest.governance.api.dto.CollectLogAppendRequest;
import com.datanest.governance.api.dto.CollectTaskInfoDTO;
import com.datanest.governance.api.dto.CollectTaskMarkStatusRequest;
import com.datanest.governance.api.dto.CollectUpsertColumnsRequest;
import com.datanest.governance.api.dto.CollectUpsertTableRequest;
import com.datanest.governance.api.dto.DetectDeletedResultDTO;
import com.datanest.governance.api.dto.UpsertColumnsResultDTO;
import com.datanest.governance.api.dto.UpsertTableResultDTO;
import com.datanest.governance.api.fallback.CollectWriteApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 采集回写域内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/collect/** 端点。
 * 微服务化 4.1：worker 采集执行过程中的治理表回写（任务状态/采集历史/执行日志/变更明细/元数据 upsert）
 * 收进 governance 服务，调用方改走本契约。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "collectWriteApi",
        fallbackFactory = CollectWriteApiFallbackFactory.class)
public interface CollectWriteApi {

    /** 查询采集任务定义（全字段，供执行器读取 scope/增量配置等） */
    @GetMapping("/collect/tasks/{id}")
    Result<CollectTaskInfoDTO> getTask(@PathVariable("id") Long id);

    /** 回写任务状态（运行中置 RUNNING / 收尾回写终态 + lastHistoryId + lastExecuteTime） */
    @PostMapping("/collect/tasks/{id}/mark-status")
    Result<Void> markTaskStatus(@PathVariable("id") Long id, @RequestBody CollectTaskMarkStatusRequest request);

    /** 初始化采集历史（RUNNING，统计列清零），返回 historyId */
    @PostMapping("/collect/histories")
    Result<Long> createHistory(@RequestBody CollectHistoryCreateRequest request);

    /** 轻量查询历史状态（手动停止轮询用） */
    @GetMapping("/collect/histories/{id}")
    Result<CollectHistoryInfoDTO> getHistory(@PathVariable("id") Long id);

    /** 收尾采集历史：终态 + 结束时间 + 耗时 + 统计列 + 错误信息 */
    @PostMapping("/collect/histories/{id}/finish")
    Result<Void> finishHistory(@PathVariable("id") Long id, @RequestBody CollectHistoryFinishRequest request);

    /** 追加执行日志（批量插入 collect_execution_log） */
    @PostMapping("/collect/histories/{id}/logs:append")
    Result<Void> appendLogs(@PathVariable("id") Long id, @RequestBody CollectLogAppendRequest request);

    /** 批量写入采集变更明细（collect_change_detail） */
    @PostMapping("/collect/histories/{id}/change-details:batch")
    Result<Void> batchChangeDetails(@PathVariable("id") Long id, @RequestBody CollectChangeDetailBatchRequest request);

    /** 元数据表 find-or-create + 更新 comment/source_status/last_collect_history_id，返回 tableId + 变更计数 */
    @PostMapping("/collect/metadata/upsert-table")
    Result<UpsertTableResultDTO> upsertTable(@RequestBody CollectUpsertTableRequest request);

    /** 元数据字段 diff upsert：逐字段 upsert + 消失的字段置 OFFLINE + 已 OFFLINE 字段复活，返回变更计数 */
    @PostMapping("/collect/metadata/upsert-columns")
    Result<UpsertColumnsResultDTO> upsertColumns(@RequestBody CollectUpsertColumnsRequest request);

    /** 删除表检测：ONLINE 但不在本次清单的表+列置 OFFLINE，返回删除计数 */
    @PostMapping("/collect/metadata/detect-deleted-tables")
    Result<DetectDeletedResultDTO> detectDeletedTables(@RequestBody CollectDetectDeletedTablesRequest request);
}
