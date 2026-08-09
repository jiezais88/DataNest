package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "资产分类树响应：分类树 + 计数（totalCount/uncategorizedCount 供前端「全部资产」「未分类」节点展示计数徽章）")
@Data
public class AssetClassificationTreeDTO {

    @Schema(description = "DOMAIN 根节点列表（TOPIC 挂 children），各节点带 tableCount")
    private List<AssetClassificationDTO> list;

    @Schema(description = "全部 ONLINE 表数（含未分类）")
    private Long totalCount;

    @Schema(description = "未分类（data_domain 为空）表数")
    private Long uncategorizedCount;
}
