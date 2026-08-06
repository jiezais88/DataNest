package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 告警对象下拉选项（支持树形层级）。
 */
@Data
public class ObjectOptionDTO {

    /** 对象 ID */
    private Long id;

    /** 对象名称 */
    private String name;

    /** 子选项 */
    private List<ObjectOptionDTO> children;
}
