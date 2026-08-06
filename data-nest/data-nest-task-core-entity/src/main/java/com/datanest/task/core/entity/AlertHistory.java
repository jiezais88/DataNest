package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警发送历史
 * 对应表 alert_history
 */
@Data
@TableName("alert_history")
public class AlertHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long alertRuleId;

    private String objectType;

    private Long objectId;

    /** 关联的质量检查批次 ID（质量对象告警时落库，便于批次详情反查告警记录；非质量告警为 NULL） */
    private Long qualityBatchId;

    /** FAILURE / TIMEOUT / SUCCESS */
    private String alertType;

    /** 实际发送的邮箱列表，分号分隔 */
    private String recipients;

    /** 邮件发送状态：SUCCESS / FAILED */
    private String sendStatus;

    private LocalDateTime sentAt;

    /** 联查对象名（非表字段，列表展示用） */
    @TableField(exist = false)
    private String objectName;

    /** 告警规则名称（冗余落库；规则删除后历史仍保留名称） */
    private String ruleName;

    /** 告警聚合明细（质量批次告警落库：每行一条命中规则「等级 + 规则名 + 详情」；非质量告警为 NULL） */
    private String summary;
}
