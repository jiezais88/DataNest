package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Top Key 调用排行项（Sprint 10 F3，含僵尸 Key）。
 */
@Data
@Schema(description = "调用方 Key 排行项")
public class StatsTopKeyDTO {

    @Schema(description = "API Key ID")
    private Long keyId;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "调用量（近 7 天 0 调用 = 僵尸 Key）")
    private Long calls;

    @Schema(description = "是否僵尸 Key（近 7 天 0 调用，前端灰显）")
    private Boolean zombie;

    @Schema(description = "绑定的 API 数（Key 管理绑定关系，0 = 未绑定）")
    private Long boundApiCount;
}
