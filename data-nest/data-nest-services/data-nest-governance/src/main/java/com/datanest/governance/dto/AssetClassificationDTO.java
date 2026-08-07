package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资产分类树节点（Sprint 7 F1，DOMAIN→TOPIC 两级）。
 */
@Data
public class AssetClassificationDTO {

    private Long id;

    /** DOMAIN / TOPIC */
    private String level;

    private String name;

    private Long parentId;

    private Integer sort;

    private List<AssetClassificationDTO> children;

    /** Sprint 7 F1 修订：该分类下的 ONLINE 表数（域=含其下所有主题，主题=精确匹配） */
    private Long tableCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
