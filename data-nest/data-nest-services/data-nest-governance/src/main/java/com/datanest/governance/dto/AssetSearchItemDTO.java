package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "资产搜索/浏览结果项（扁平结构，区别于 search-tree 树结构）")
@Data
public class AssetSearchItemDTO {

    @Schema(description = "表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "表注释")
    private String tableComment;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名")
    private String schemaName;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源名称")
    private String datasourceName;

    @Schema(description = "数据源类型（如 MYSQL/DORIS）")
    private String datasourceType;

    @Schema(description = "质量评分（未配置规则为 null，前端展示「—」）")
    private BigDecimal qualityScore;

    @Schema(description = "健康度（EXCELLENT/GOOD/WARNING/BAD）")
    private String healthLevel;

    @Schema(description = "数据域（一级分类名）")
    private String dataDomain;

    @Schema(description = "主题（二级分类名）")
    private String dataTopic;

    @Schema(description = "负责人用户 ID", example = "1234567890123456789")
    private Long ownerUserId;

    @Schema(description = "负责人用户名")
    private String ownerName;

    @Schema(description = "相关度得分（仅搜索接口返回；表名命中 100、注释 60、字段 40、负责人 20，表名前缀命中加成）")
    private Integer score;

    @Schema(description = "表标签名数组（Sprint 8 DC-06，搜索/浏览回填；无标签为空数组）")
    private List<String> tags;

    @Schema(description = "最近 30 天访问数（Sprint 8 DC-09；2026-08-10 起搜索/浏览/收藏/关注/热门全场景统一回填，无访问为 0）")
    private Long viewCount;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
