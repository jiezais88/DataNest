package com.datanest.governance.dto;

import lombok.Data;

/**
 * 分类新增/编辑请求（Sprint 7 F1）。
 */
@Data
public class ClassificationSaveRequest {

    /** DOMAIN / TOPIC */
    private String level;

    private String name;

    /** TOPIC 必填（指向 DOMAIN）；DOMAIN 必须为 null */
    private Long parentId;

    private Integer sort;
}
