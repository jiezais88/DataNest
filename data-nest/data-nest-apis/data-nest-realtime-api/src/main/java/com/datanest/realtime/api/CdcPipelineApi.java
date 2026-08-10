package com.datanest.realtime.api;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.api.fallback.CdcPipelineApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 实时 CDC 管道内部 Feign 契约（Sprint 8 F2）。
 */
@FeignClient(name = "data-nest-realtime", path = "/realtime/internal", contextId = "cdcPipelineApi",
        fallbackFactory = CdcPipelineApiFallbackFactory.class)
public interface CdcPipelineApi {

    /** 按源数据源查询引用它的 CDC 管道（删除数据源前置校验用） */
    @GetMapping("/cdc/pipelines/by-datasource")
    Result<List<CdcPipelineReferenceDTO>> listByDatasource(@RequestParam("datasourceId") Long datasourceId);
}
