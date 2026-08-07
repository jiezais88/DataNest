package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 血缘记录批量写入请求。
 * <p>
 * 对齐原 SqlLineageExtractor.saveRecords 的语义：批量插入 lineage_record。
 */
@Data
public class LineageRecordBatchRequest {

    /** 血缘记录列表 */
    private List<LineageRecordItemDTO> records;
}
