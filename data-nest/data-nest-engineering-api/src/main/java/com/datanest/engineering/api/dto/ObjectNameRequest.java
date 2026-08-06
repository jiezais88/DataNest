package com.datanest.engineering.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 对象名称批量查询请求。
 */
@Data
public class ObjectNameRequest {

    /** 对象类型 */
    private String objectType;

    /** 对象 ID 列表 */
    private List<Long> ids;
}
