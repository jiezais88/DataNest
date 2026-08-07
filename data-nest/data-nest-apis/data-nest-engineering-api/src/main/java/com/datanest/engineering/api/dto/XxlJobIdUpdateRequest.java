package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 同步任务 xxl_job_id 回写请求。
 */
@Data
public class XxlJobIdUpdateRequest {

    private Integer xxlJobId;
}
