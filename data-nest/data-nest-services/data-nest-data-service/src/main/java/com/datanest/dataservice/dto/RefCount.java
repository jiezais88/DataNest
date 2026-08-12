package com.datanest.dataservice.dto;

import lombok.Data;

/**
 * 分组计数投影（Mapper @Select 用）：refId = 分组键（apiId/keyId），cnt = 计数。
 */
@Data
public class RefCount {

    private Long refId;

    private Long cnt;
}
