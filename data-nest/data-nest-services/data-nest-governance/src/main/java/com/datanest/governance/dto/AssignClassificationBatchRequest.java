package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量分配分类请求（Sprint 7 F1 修订）。dataDomain/dataTopic 均为空表示批量清除分类。
 */
@Data
public class AssignClassificationBatchRequest {

    /** 目标表 ID 列表 */
    private List<Long> tableIds;

    /** 数据域（一级分类名） */
    private String dataDomain;

    /** 主题（二级分类名），须属于 dataDomain */
    private String dataTopic;
}
