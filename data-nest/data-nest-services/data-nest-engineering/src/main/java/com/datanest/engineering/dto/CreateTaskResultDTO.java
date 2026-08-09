package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 从模板一键创建任务结果（Sprint 7 DD-09）。
 */
@Schema(description = "从模板一键创建任务结果")
@Data
@AllArgsConstructor
public class CreateTaskResultDTO {

    /** 任务类型：SYNC / COLLECT */
    @Schema(description = "任务类型（SYNC/COLLECT）")
    private String taskType;

    /** 新建任务 ID（SYNC→sync_job.id / COLLECT→collect_task.id） */
    @Schema(description = "新建任务 ID（SYNC→sync_job.id / COLLECT→collect_task.id）", example = "1234567890123456789")
    private Long taskId;
}
