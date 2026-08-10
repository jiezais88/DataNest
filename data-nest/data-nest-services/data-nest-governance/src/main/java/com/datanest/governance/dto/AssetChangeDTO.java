package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "表变更动态（复用 collect_change_detail 原始字段，前端按类型渲染摘要）")
@Data
public class AssetChangeDTO {

    @Schema(description = "变更类型（ADDED_TABLE/DELETED_TABLE/MODIFIED_TABLE/ADDED_COLUMN/DELETED_COLUMN/MODIFIED_COLUMN_*）")
    private String changeType;

    @Schema(description = "字段名（表级变更为 null）")
    private String columnName;

    @Schema(description = "旧值")
    private String oldValue;

    @Schema(description = "新值")
    private String newValue;

    @Schema(description = "变更时间（ISO 8601）")
    private LocalDateTime changeTime;
}
