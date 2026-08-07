package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 卡死 RUNNING 收割请求（start_time 早于 now - stuckBeforeMinutes 的记录）。
 */
@Data
public class ReapStuckRequest {

    private Integer stuckBeforeMinutes;
}
