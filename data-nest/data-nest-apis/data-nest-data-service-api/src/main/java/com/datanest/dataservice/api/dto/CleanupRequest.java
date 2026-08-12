package com.datanest.dataservice.api.dto;

import lombok.Data;

/**
 * 低频运维清理请求（数据服务 SQL 查询历史清理用）。
 */
@Data
public class CleanupRequest {

    /** 保留天数；null 时由服务端按默认值（30 天）处理 */
    private Integer retainDays;
}
