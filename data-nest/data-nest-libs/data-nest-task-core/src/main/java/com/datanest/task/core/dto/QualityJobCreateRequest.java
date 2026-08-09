package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 质量任务新增请求（Sprint 6 配置层）。
 * <p>
 * 调度方式（D-D1）：手动是默认能力；定时/自动可叠加可选配。
 * 校验：勾选定时（scheduled_enabled=1）则必填 cron；告警等级限 SEVERE_ONLY / SEVERE_WARNING。
 */
@Schema(description = "质量任务新增请求")
@Data
public class QualityJobCreateRequest {

    @Schema(description = "任务名称")
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不能超过 100 字符")
    private String name;

    @Schema(description = "任务描述")
    @Size(max = 500, message = "任务描述不能超过 500 字符")
    private String description;

    @Schema(description = "启用状态（1 启用，0 停用；默认 1）")
    private Integer enabled = 1;

    @Schema(description = "是否开定时调度（1 开，0 关；默认 0）")
    private Integer scheduledEnabled = 0;

    @Schema(description = "定时 cron 表达式（scheduledEnabled=1 时必填）")
    private String cron;

    @Schema(description = "是否任务完成自动触发（1 开，0 关；默认 0）")
    private Integer autoTriggerEnabled = 0;

    @Schema(description = "自动触发绑定对象类型（DAG_NODE/SYNC_JOB/COLLECT_TASK）")
    @Pattern(regexp = "^(DAG_NODE|SYNC_JOB|COLLECT_TASK)?$", message = "自动触发对象类型非法")
    private String autoTriggerObjectType;

    @Schema(description = "自动触发绑定对象 ID", example = "1234567890123456789")
    private Long autoTriggerObjectId;

    @Schema(description = "告警触发等级（SEVERE_ONLY/SEVERE_WARNING）")
    @Pattern(regexp = "^(SEVERE_ONLY|SEVERE_WARNING)$", message = "告警等级非法（仅支持 SEVERE_ONLY / SEVERE_WARNING）")
    private String alertLevel = "SEVERE_WARNING";

    @Schema(description = "执行超时阈值（分钟）；null/不传 = 不启用超时检测，>0 才生效")
    @Min(value = 1, message = "超时阈值必须大于 0 分钟")
    private Integer timeoutMinutes;

    @Schema(description = "引用的质量规则 ID 集合（从规则库多对多勾选）")
    private List<Long> ruleIds;

    @AssertTrue(message = "开启定时调度时必须填写 cron")
    public boolean isScheduleConfigValid() {
        // scheduled_enabled 为空视为关闭定时
        if (scheduledEnabled == null || scheduledEnabled != 1) {
            return true;
        }
        return StringUtils.hasText(cron);
    }
}
