package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * API Key 简要信息（API 详情「绑定 Key」列表用）。
 */
@Data
@Schema(description = "API Key 简要信息")
public class ApiKeyBriefDTO {

    @Schema(description = "Key ID")
    private Long id;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "状态：ENABLED / DISABLED")
    private String status;
}
