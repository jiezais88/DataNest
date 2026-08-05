package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 告警规则接收用户关联
 * 对应表 alert_rule_user
 * Sprint 5：收件人改为选择平台用户，发送时反查 sys_user.email。
 */
@Data
@TableName("alert_rule_user")
public class AlertRuleUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long alertRuleId;

    private Long userId;
}
