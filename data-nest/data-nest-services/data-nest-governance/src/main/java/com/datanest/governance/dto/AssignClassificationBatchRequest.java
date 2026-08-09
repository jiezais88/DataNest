package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "批量分配分类请求（dataDomain/dataTopic 均为空表示批量清除分类）")
@Data
public class AssignClassificationBatchRequest {

    @Schema(description = "目标表 ID 列表", example = "[\"1234567890123456789\"]")
    private List<Long> tableIds;

    @Schema(description = "数据域（一级分类名）")
    private String dataDomain;

    @Schema(description = "主题（二级分类名），须属于 dataDomain")
    private String dataTopic;
}
