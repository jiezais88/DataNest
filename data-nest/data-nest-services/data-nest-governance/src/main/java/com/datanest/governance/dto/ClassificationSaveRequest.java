package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "分类新增/编辑请求")
@Data
public class ClassificationSaveRequest {

    @Schema(description = "层级（DOMAIN/TOPIC）")
    private String level;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "父分类 ID（TOPIC 必填，指向 DOMAIN；DOMAIN 必须为 null）", example = "1234567890123456789")
    private Long parentId;

    @Schema(description = "排序号")
    private Integer sort;
}
