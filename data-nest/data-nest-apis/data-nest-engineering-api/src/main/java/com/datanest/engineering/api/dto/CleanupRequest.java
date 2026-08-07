package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 历史清理请求（删除 created_at 早于 now - retainDays 的记录）。
 */
@Data
public class CleanupRequest {

    private Integer retainDays;
}
