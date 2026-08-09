package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "元数据备注更新请求")
@Data
public class MetadataRemarkRequest {

    @Schema(description = "备注内容")
    @NotNull
    private String remark;
}
