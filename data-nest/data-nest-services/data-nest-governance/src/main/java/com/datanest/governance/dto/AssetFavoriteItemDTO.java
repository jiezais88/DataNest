package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "我的收藏列表项（资产卡片字段 + 收藏时间）")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetFavoriteItemDTO extends AssetSearchItemDTO {

    @Schema(description = "收藏时间（ISO 8601）")
    private LocalDateTime favoritedAt;
}
