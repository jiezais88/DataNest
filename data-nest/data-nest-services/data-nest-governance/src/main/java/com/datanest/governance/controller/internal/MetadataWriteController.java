package com.datanest.governance.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.MetadataRefreshIfExistsRequest;
import com.datanest.governance.api.dto.MetadataRegisterRequest;
import com.datanest.governance.api.dto.MetadataRemoveRequest;
import com.datanest.governance.service.internal.MetadataWriteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元数据写入 + 血缘域内部接口（实现 governance-api 的 MetadataWriteApi 契约）。
 * <p>
 * 仅供服务间内部调用（task 服务上报 Doris 元数据注册/刷新/移除与血缘记录），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class MetadataWriteController {

    private final MetadataWriteService metadataWriteService;

    public MetadataWriteController(MetadataWriteService metadataWriteService) {
        this.metadataWriteService = metadataWriteService;
    }

    /**
     * 元数据注册（一个事务端点）：findOrCreateTable + refreshColumns + column_count 更新，返回 tableId。
     */
    @PostMapping("/metadata/register")
    public Result<Long> register(@RequestBody MetadataRegisterRequest request) {
        return Result.ok(metadataWriteService.register(request));
    }

    /**
     * 表已存在才刷新 column_count；不存在则静默跳过。
     */
    @PostMapping("/metadata/refresh-if-exists")
    public Result<Void> refreshIfExists(@RequestBody MetadataRefreshIfExistsRequest request) {
        metadataWriteService.refreshIfExists(request);
        return Result.ok(null);
    }

    /**
     * DROP TABLE 场景：先删列再删表；不存在则静默跳过。
     */
    @PostMapping("/metadata/remove")
    public Result<Void> remove(@RequestBody MetadataRemoveRequest request) {
        metadataWriteService.remove(request);
        return Result.ok(null);
    }

    /**
     * 血缘记录批量写入，返回插入条数。
     */
    @PostMapping("/lineage/records:batch")
    public Result<Integer> saveLineageRecords(@RequestBody LineageRecordBatchRequest request) {
        return Result.ok(metadataWriteService.saveLineageRecords(request));
    }
}
