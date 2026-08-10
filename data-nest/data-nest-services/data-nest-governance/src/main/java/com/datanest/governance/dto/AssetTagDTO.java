package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "资产标签字典项（标签云）")
@Data
public class AssetTagDTO {

    @Schema(description = "标签 ID", example = "1234567890123456789")
    private Long tagId;

    @Schema(description = "标签名")
    private String tagName;

    @Schema(description = "绑定该标签的表数（标签云大小区分用）")
    private Long refCount;
}
