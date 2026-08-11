package com.datanest.realtime.controller;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.service.CdcPipelineService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时 CDC 服务内部接口（实现 realtime-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用，路径挂在 context-path /realtime 下（servlet path 以 /internal/ 开头），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class CdcInternalController {

    private final CdcPipelineService pipelineService;

    public CdcInternalController(CdcPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /** 按源数据源查询引用它的 CDC 管道（engineering 删除数据源前置校验用） */
    @GetMapping("/cdc/pipelines/by-datasource")
    public Result<List<CdcPipelineReferenceDTO>> listByDatasource(@RequestParam Long datasourceId) {
        return Result.ok(pipelineService.listByDatasource(datasourceId));
    }

    /** 批量查询管道 id → name（Sprint 9 F3：app-alert 告警对象名反查/可选对象下拉；ids 为空返回全部） */
    @GetMapping("/cdc/pipelines/names")
    public Result<Map<Long, String>> names(@RequestParam(value = "ids", required = false) List<Long> ids) {
        return Result.ok(pipelineService.names(ids));
    }

    /** 条件刷新 Doris 湖仓 catalog（app-job 定时触发）：仅存在 RUNNING 管道时刷新，返回是否实际刷新 */
    @PostMapping("/cdc/pipelines/refresh-catalog-if-running")
    public Result<Boolean> refreshCatalogIfRunning() {
        return Result.ok(pipelineService.refreshCatalogIfRunning());
    }
}
