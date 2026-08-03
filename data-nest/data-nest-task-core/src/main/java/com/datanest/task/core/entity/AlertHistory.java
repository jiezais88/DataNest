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
}
