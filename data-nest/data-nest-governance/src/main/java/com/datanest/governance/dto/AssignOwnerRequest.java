package com.datanest.governance.dto;

import lombok.Data;

/**
 * 表配置负责人请求（Sprint 7 F1）。ownerUserId 为 null 表示清除负责人。
 */
@Data
public class AssignOwnerRequest {

    private Long ownerUserId;
}
