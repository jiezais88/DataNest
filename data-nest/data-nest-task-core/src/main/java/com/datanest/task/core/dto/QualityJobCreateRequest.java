package com.datanest.task.core.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 质量任务新增请求（Sprint 6 配置层）。
 * <p>
 * 调度方式（D-D1）：手动是默认能力；定时/自动可叠加可选配。
 * 校验：勾选定时（scheduled_enabled=1）则必填 cron；告警等级限 SEVERE_ONLY / SEVERE_WARNING。
 */
@Data
public class QualityJobCreateRequest {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不能超过 100 字符")
    private String name;

    @Size(max = 500, message = "任务描述不能超过 500 字符")
    private String description;

    /** 可选，数据源范围（用于选表过滤） */
    private Long datasourceId;

    /** 启用状态：默认 1 启用 */
    private Integer enabled = 1;

    /** 是否开定时调度：默认 0 关 */
    private Integer scheduledEnabled = 0;

    /** 定时 cron（scheduled_enabled=1 时必填） */
    private String cron;

    /** 是否任务完成自动触发：默认 0 关 */
    private Integer autoTriggerEnabled = 0;

    /** 自动触发绑定对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK */
    @Pattern(regexp = "^(DAG_NODE|SYNC_JOB|COLLECT_TASK)?$", message = "自动触发对象类型非法")
    private String autoTriggerObjectType;

    /** 自动触发绑定对象 ID */
    private Long autoTriggerObjectId;

    /** 告警触发等级：SEVERE_ONLY / SEVERE_WARNING */
    @Pattern(regexp = "^(SEVERE_ONLY|SEVERE_WARNING)$", message = "告警等级非法（仅支持 SEVERE_ONLY / SEVERE_WARNING）")
    private String alertLevel = "SEVERE_WARNING";

    @AssertTrue(message = "开启定时调度时必须填写 cron")
    public boolean isScheduleConfigValid() {
        // scheduled_enabled 为空视为关闭定时
        if (scheduledEnabled == null || scheduledEnabled != 1) {
            return true;
        }
        return StringUtils.hasText(cron);
    }
}
