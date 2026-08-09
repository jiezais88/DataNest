package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "元数据源（数据源列表项）")
@Data
public class MetadataDatasourceDTO {

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "数据源名称")
    private String name;

    @Schema(description = "数据源类型（如 MYSQL/DORIS）")
    private String type;

    @Schema(description = "连接是否存在")
    private Boolean exists;

    @Schema(description = "数据来源类型（BUILTIN_DORIS/EXTERNAL）")
    private String sourceType;
}
