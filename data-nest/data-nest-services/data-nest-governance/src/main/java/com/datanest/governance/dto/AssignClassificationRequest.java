package com.datanest.governance.dto;

import lombok.Data;

/**
 * 表分配分类请求（Sprint 7 F1）。两者均为 null/空表示清除分类。
 */
@Data
public class AssignClassificationRequest {

    /** 数据域（一级分类名） */
    private String dataDomain;

    /** 主题（二级分类名），须属于 dataDomain */
    private String dataTopic;
}
