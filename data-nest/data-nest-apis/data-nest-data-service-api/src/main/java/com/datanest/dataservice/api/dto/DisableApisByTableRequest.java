package com.datanest.dataservice.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DisableApisByTableRequest {
    /** 需要下线 API 的元数据表 ID 列表（metadata_table.id） */
    private List<Long> metadataTableIds;
}
