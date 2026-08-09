package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表分配分类请求（两者均为空表示清除分类）")
@Data
public class AssignClassificationRequest {

    @Schema(description = "数据域（一级分类名）")
    private String dataDomain;

    @Schema(description = "主题（二级分类名），须属于 dataDomain")
    private String dataTopic;
}
