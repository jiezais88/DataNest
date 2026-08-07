package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 同步任务 scheduler_job_id（PowerJob jobId）回写请求。
 */
@Data
public class SchedulerJobIdUpdateRequest {

    private Long schedulerJobId;
}
