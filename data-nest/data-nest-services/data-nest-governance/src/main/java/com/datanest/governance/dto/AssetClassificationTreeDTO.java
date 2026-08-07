package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * 资产分类树响应（Sprint 7 F1 修订）：树 + 计数。
 * totalCount/uncategorizedCount 供前端「全部资产」「未分类」两个特殊节点展示计数徽章。
 */
@Data
public class AssetClassificationTreeDTO {

    /** DOMAIN 根节点列表（TOPIC 挂 children），各节点带 tableCount */
    private List<AssetClassificationDTO> list;

    /** 全部 ONLINE 表数（含未分类） */
    private Long totalCount;

    /** 未分类（data_domain 为空）表数 */
    private Long uncategorizedCount;
}
