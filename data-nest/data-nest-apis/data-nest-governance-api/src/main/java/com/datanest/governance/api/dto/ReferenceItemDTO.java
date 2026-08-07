package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 数据源引用项（删除数据源前的引用检查）。
 */
@Data
public class ReferenceItemDTO {

    private Long id;

    private String name;
}
