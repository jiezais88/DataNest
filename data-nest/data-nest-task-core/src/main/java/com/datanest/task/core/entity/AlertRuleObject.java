package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则关联对象（支持一条规则绑定多个对象）。
 * 对应表 alert_rule_object。
 */
@Data
@TableName("alert_rule_object")
public class AlertRuleObject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long alertRuleId;

    private String objectType;

    private Long objectId;

    private String objectName;

    private LocalDateTime createdAt;
}
