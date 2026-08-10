package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表标签项（某表当前绑定的标签）")
@Data
public class AssetTableTagDTO {

    @Schema(description = "标签 ID", example = "1234567890123456789")
    private Long tagId;

    @Schema(description = "标签名")
    private String tagName;
}
