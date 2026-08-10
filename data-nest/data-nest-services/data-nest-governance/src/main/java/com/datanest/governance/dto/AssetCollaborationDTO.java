package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "资产协作状态聚合（详情页头部一次拉取：标签 + 当前用户收藏/关注状态 + 热度 + 评论数）")
@Data
public class AssetCollaborationDTO {

    @Schema(description = "该表当前绑定的标签列表")
    private List<AssetTableTagDTO> tags;

    @Schema(description = "当前用户是否已收藏")
    private Boolean favorited;

    @Schema(description = "当前用户是否已关注")
    private Boolean followed;

    @Schema(description = "最近 30 天访问数（热度）")
    private Long viewCount30d;

    @Schema(description = "有效评论数（deleted=0）")
    private Long commentCount;
}
