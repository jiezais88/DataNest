package com.datanest.dataservice.dto;

import lombok.Data;

/**
 * 分组计数投影（Mapper @Select 用）：refId = 分组键（apiId/keyId），cnt = 计数，
 * keyName = 调用日志里冗余的 Key 名快照（Key 物理删除后展示原名）。
 */
@Data
public class RefCount {

    private Long refId;

    private Long cnt;

    private String keyName;
}
