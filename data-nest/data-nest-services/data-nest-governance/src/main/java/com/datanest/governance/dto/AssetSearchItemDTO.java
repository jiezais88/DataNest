package com.datanest.governance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产搜索/浏览结果项（Sprint 7 F1，扁平结构，区别于 search-tree 树结构）。
 */
@Data
public class AssetSearchItemDTO {

    private Long tableId;

    private String tableName;

    private String tableComment;

    private String databaseName;

    private String schemaName;

    private Long datasourceId;

    private String datasourceName;

    private String datasourceType;

    /** 质量评分（未配置规则为 null，前端展示「—」） */
    private BigDecimal qualityScore;

    /** 健康度：EXCELLENT/GOOD/WARNING/BAD */
    private String healthLevel;

    private String dataDomain;

    private String dataTopic;

    private Long ownerUserId;

    private String ownerName;

    /** 相关度得分（仅搜索接口返回；表名命中 100、注释 60、字段 40、负责人 20，表名前缀命中加成） */
    private Integer score;

    private LocalDateTime updatedAt;
}
