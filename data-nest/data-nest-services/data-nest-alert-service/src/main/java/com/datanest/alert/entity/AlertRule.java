package com.datanest.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用告警规则
 * 对应表 alert_rule
 * Sprint 5：统一表达 DAG / 同步任务 / 采集任务的告警规则。
 */
@Data
@TableName("alert_rule")
public class AlertRule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则名称（用户自定义，必填；同一 object_type 下唯一） */
    private String name;

    /** 对象类型：DAG / SYNC_JOB / COLLECT_TASK / QUALITY */
    private String objectType;

    /** 对象名称冗余，便于列表展示；多对象时以「、」拼接 */
    private String objectName;

    /** 触发条件 JSON 数组字符串，如 ["FAILURE","TIMEOUT"] */
    private String triggerConditions;

    /** 超时阈值（分钟），默认 30 */
    private Integer timeoutMinutes;

    /** 1 启用，0 关闭 */
    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
