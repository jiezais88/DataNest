package com.datanest.engineering.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 从模板一键创建任务结果（Sprint 7 DD-09）。
 */
@Data
@AllArgsConstructor
public class CreateTaskResultDTO {

    /** 任务类型：SYNC / COLLECT */
    private String taskType;

    /** 新建任务 ID（SYNC→sync_job.id / COLLECT→collect_task.id） */
    private Long taskId;
}
