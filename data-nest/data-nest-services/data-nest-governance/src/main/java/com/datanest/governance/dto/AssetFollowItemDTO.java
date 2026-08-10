package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "我的关注列表项（资产卡片字段 + 关注时间 + 最近一次变更动态）")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetFollowItemDTO extends AssetSearchItemDTO {

    @Schema(description = "关注时间（ISO 8601）")
    private LocalDateTime followedAt;

    @Schema(description = "最近一次采集变更动态（无变更为 null）")
    private AssetChangeDTO latestChange;
}
