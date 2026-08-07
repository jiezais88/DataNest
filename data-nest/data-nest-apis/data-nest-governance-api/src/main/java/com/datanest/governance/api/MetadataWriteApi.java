package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.MetadataRefreshIfExistsRequest;
import com.datanest.governance.api.dto.MetadataRegisterRequest;
import com.datanest.governance.api.dto.MetadataRemoveRequest;
import com.datanest.governance.api.fallback.MetadataWriteApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 治理服务元数据写入 + 血缘域内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/** 端点。
 * 微服务化 4.1：task 服务不再直连治理库，元数据注册/刷新/移除与血缘记录写入改走本契约。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "metadataWriteApi",
        fallbackFactory = MetadataWriteApiFallbackFactory.class)
public interface MetadataWriteApi {

    /**
     * 元数据注册（一个事务端点）：findOrCreateTable + refreshColumns + column_count 更新。
     * 对齐原 MetadataRegistrationService.registerTable 语义，返回 tableId。
     */
    @PostMapping("/metadata/register")
    Result<Long> register(@RequestBody MetadataRegisterRequest request);

    /** 表已存在才刷新（列结构 + column_count）；不存在则静默跳过 */
    @PostMapping("/metadata/refresh-if-exists")
    Result<Void> refreshIfExists(@RequestBody MetadataRefreshIfExistsRequest request);

    /** DROP TABLE 场景：先删列再删表；不存在则静默跳过 */
    @PostMapping("/metadata/remove")
    Result<Void> remove(@RequestBody MetadataRemoveRequest request);

    /** 血缘记录批量写入，对齐原 SqlLineageExtractor.saveRecords 语义，返回插入条数 */
    @PostMapping("/lineage/records:batch")
    Result<Integer> saveLineageRecords(@RequestBody LineageRecordBatchRequest request);
}
