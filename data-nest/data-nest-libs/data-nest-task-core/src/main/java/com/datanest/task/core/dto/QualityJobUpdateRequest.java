package com.datanest.task.core.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 质量任务更新请求（Sprint 6 配置层）。
 * <p>
 * 更新语义：name/enabled/scheduled_enabled/cron/auto_trigger_object_type/alert_level 覆盖更新，
 * description 允许置空（传 null 即清空）。
 */
@Data
public class QualityJobUpdateRequest {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不能超过 100 字符")
    private String name;

    @Size(max = 500, message = "任务描述不能超过 500 字符")
    private String description;

    private Integer enabled;

    private Integer scheduledEnabled;

    private String cron;

    private Integer autoTriggerEnabled;

    @Pattern(regexp = "^(DAG_NODE|SYNC_JOB|COLLECT_TASK)?$", message = "自动触发对象类型非法")
    private String autoTriggerObjectType;

    private Long autoTriggerObjectId;

    @Pattern(regexp = "^(SEVERE_ONLY|SEVERE_WARNING)?$", message = "告警等级非法（仅支持 SEVERE_ONLY / SEVERE_WARNING）")
    private String alertLevel;

    /** 执行超时阈值（分钟），null/不传 = 不更新该字段（保留原值）；>0 才生效（0 视为禁用超时） */
    @Min(value = 0, message = "超时阈值必须 ≥ 0")
    private Integer timeoutMinutes;

    /** 引用的质量规则 ID 集合（全量覆盖关联，Sprint 7 新增） */
    private List<Long> ruleIds;

    @AssertTrue(message = "开启定时调度时必须填写 cron")
    public boolean isScheduleConfigValid() {
        if (scheduledEnabled == null || scheduledEnabled != 1) {
            return true;
        }
        return StringUtils.hasText(cron);
    }
}
