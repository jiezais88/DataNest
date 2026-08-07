package com.datanest.governance.controller.internal;

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
import com.datanest.governance.service.internal.CollectWriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集回写域内部接口（实现 governance-api 的 CollectWriteApi 契约，仅镜像路径不 implement）。
 * <p>
 * 仅供服务间内部调用（worker 采集执行回写任务状态/采集历史/执行日志/变更明细/元数据），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class CollectWriteController {

    private final CollectWriteService collectWriteService;

    public CollectWriteController(CollectWriteService collectWriteService) {
        this.collectWriteService = collectWriteService;
    }

    /** 查询采集任务定义（全字段）；不存在返回 null，由调用方 fail-fast。 */
    @GetMapping("/collect/tasks/{id}")
    public Result<CollectTaskInfoDTO> getTask(@PathVariable Long id) {
        return Result.ok(collectWriteService.getTask(id));
    }

    /** 回写任务状态（运行中置 RUNNING / 收尾回写终态 + lastHistoryId + lastExecuteTime）。 */
    @PostMapping("/collect/tasks/{id}/mark-status")
    public Result<Void> markTaskStatus(@PathVariable Long id, @RequestBody CollectTaskMarkStatusRequest request) {
        collectWriteService.markTaskStatus(id, request);
        return Result.ok(null);
    }

    /** 初始化采集历史（RUNNING，统计列清零），返回 historyId。 */
    @PostMapping("/collect/histories")
    public Result<Long> createHistory(@RequestBody CollectHistoryCreateRequest request) {
        return Result.ok(collectWriteService.createHistory(request));
    }

    /** 轻量查询历史状态（手动停止轮询用）；不存在返回 null。 */
    @GetMapping("/collect/histories/{id}")
    public Result<CollectHistoryInfoDTO> getHistory(@PathVariable Long id) {
        return Result.ok(collectWriteService.getHistory(id));
    }

    /** 收尾采集历史：终态 + 结束时间 + 耗时 + 统计列 + 错误信息。 */
    @PostMapping("/collect/histories/{id}/finish")
    public Result<Void> finishHistory(@PathVariable Long id, @RequestBody CollectHistoryFinishRequest request) {
        collectWriteService.finishHistory(id, request);
        return Result.ok(null);
    }

    /** 追加执行日志（批量插入 collect_execution_log）。 */
    @PostMapping("/collect/histories/{id}/logs:append")
    public Result<Void> appendLogs(@PathVariable Long id, @RequestBody CollectLogAppendRequest request) {
        collectWriteService.appendLogs(id, request);
        return Result.ok(null);
    }

    /** 批量写入采集变更明细（collect_change_detail）。 */
    @PostMapping("/collect/histories/{id}/change-details:batch")
    public Result<Void> batchChangeDetails(@PathVariable Long id, @RequestBody CollectChangeDetailBatchRequest request) {
        collectWriteService.batchChangeDetails(id, request);
        return Result.ok(null);
    }

    /** 元数据表 find-or-create + 更新 comment/source_status/last_collect_history_id，返回 tableId + 变更计数。 */
    @PostMapping("/collect/metadata/upsert-table")
    public Result<UpsertTableResultDTO> upsertTable(@RequestBody CollectUpsertTableRequest request) {
        return Result.ok(collectWriteService.upsertTable(request));
    }

    /** 元数据字段 diff upsert：逐字段 upsert + 消失的字段置 OFFLINE + 已 OFFLINE 字段复活，返回变更计数。 */
    @PostMapping("/collect/metadata/upsert-columns")
    public Result<UpsertColumnsResultDTO> upsertColumns(@RequestBody CollectUpsertColumnsRequest request) {
        return Result.ok(collectWriteService.upsertColumns(request));
    }

    /** 删除表检测：ONLINE 但不在本次清单的表+列置 OFFLINE，返回删除计数。 */
    @PostMapping("/collect/metadata/detect-deleted-tables")
    public Result<DetectDeletedResultDTO> detectDeletedTables(@RequestBody CollectDetectDeletedTablesRequest request) {
        return Result.ok(collectWriteService.detectDeletedTables(request));
    }
}
