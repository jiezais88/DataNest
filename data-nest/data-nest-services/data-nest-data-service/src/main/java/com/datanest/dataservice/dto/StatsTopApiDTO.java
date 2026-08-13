package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Top API 调用排行项（Sprint 10 F3）。
 */
@Data
@Schema(description = "Top API 调用排行项")
public class StatsTopApiDTO {

    @Schema(description = "API ID")
    private Long apiId;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径")
    private String path;

    @Schema(description = "是否已删除（软删 deleted=1 或实体已物理清除；前端灰显 + 不提供详情跳转）")
    private Boolean deleted;

    @Schema(description = "调用量")
    private Long calls;
}
