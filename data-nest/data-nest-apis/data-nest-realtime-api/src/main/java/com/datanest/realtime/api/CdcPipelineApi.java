package com.datanest.realtime.api;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.api.dto.CdcPipelineSubscribeDTO;
import com.datanest.realtime.api.fallback.CdcPipelineApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 实时 CDC 管道内部 Feign 契约（Sprint 8 F2；Sprint 9 F3 新增对象名反查）。
 */
@FeignClient(name = "data-nest-realtime", path = "/realtime/internal", contextId = "cdcPipelineApi",
        fallbackFactory = CdcPipelineApiFallbackFactory.class)
public interface CdcPipelineApi {

    /** 按源数据源查询引用它的 CDC 管道（删除数据源前置校验用） */
    @GetMapping("/cdc/pipelines/by-datasource")
    Result<List<CdcPipelineReferenceDTO>> listByDatasource(@RequestParam("datasourceId") Long datasourceId);

    /**
     * 批量查询管道 id → name（Sprint 9 F3：app-alert 告警对象名反查/可选对象下拉）。
     * ids 为空时返回全部管道（对象下拉场景）；读路径 fail-open：调用方降级空 Map 不阻断。
     */
    @GetMapping("/cdc/pipelines/names")
    Result<Map<Long, String>> names(@RequestParam(value = "ids", required = false) List<Long> ids);

    /**
     * Doris 湖仓 catalog 条件刷新（app-job 定时触发，2026-08-11 验收反馈：
     * 不再要求用户手动点「刷新 Catalog」）：仅当存在 RUNNING 管道时执行 REFRESH CATALOG，
     * 返回是否实际刷新。调度场景 fail-closed 无意义，失败本轮跳过下轮再来。
     */
    @PostMapping("/cdc/pipelines/refresh-catalog-if-running")
    Result<Boolean> refreshCatalogIfRunning();

    /**
     * 查询管道订阅信息（F4 WebSocket 订阅校验：状态 + 源数据源/库 + 源表清单，供数据服务反查敏感度）。
     * 管道不存在返回 null（data-service 据此拒绝订阅，fail-closed）。
     */
    @GetMapping("/cdc/pipelines/{id}/subscribe")
    Result<CdcPipelineSubscribeDTO> getSubscribeInfo(@PathVariable("id") Long id);
}
