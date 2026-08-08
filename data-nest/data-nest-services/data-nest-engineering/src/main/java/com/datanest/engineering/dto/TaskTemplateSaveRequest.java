package com.datanest.engineering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/编辑任务模板请求（Sprint 7 DD-09）。
 * <p>
 * configTemplate 与 sourceTaskId 二选一：sourceTaskId 非空时从既有任务配置生成模板（另存为），
 * 否则使用 configTemplate 原文。编辑时两者都缺省 = 仅改名称/说明，保留原配置。
 */
@Data
public class TaskTemplateSaveRequest {

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称最多 100 个字符")
    private String name;

    @NotBlank(message = "模板类型不能为空")
    private String type;

    @Size(max = 500, message = "模板说明最多 500 个字符")
    private String description;

    /** 模板 JSON 原文（另存为场景忽略） */
    private String configTemplate;

    /** 从既有任务另存为模板：任务 ID（SYNC→sync_job.id / COLLECT→collect_task.id） */
    private Long sourceTaskId;
}
