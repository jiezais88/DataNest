package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "资产分类树节点（DOMAIN→TOPIC 两级）")
@Data
public class AssetClassificationDTO {

    @Schema(description = "分类 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "层级（DOMAIN/TOPIC）")
    private String level;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "父分类 ID（DOMAIN 为 null）", example = "1234567890123456789")
    private Long parentId;

    @Schema(description = "排序号")
    private Integer sort;

    @Schema(description = "子分类列表")
    private List<AssetClassificationDTO> children;

    @Schema(description = "该分类下的 ONLINE 表数（域=含其下所有主题，主题=精确匹配）")
    private Long tableCount;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
