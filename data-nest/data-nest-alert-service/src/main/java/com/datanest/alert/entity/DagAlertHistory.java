package com.datanest.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 告警发送记录（防重发）
 * 对应表 dag_alert_history
 */
@Data
@TableName("dag_alert_history")
public class DagAlertHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long executionId;

    private String nodeId;

    private String alertType;       // FAILURE / TIMEOUT / SUCCESS

    private String recipients;

    private LocalDateTime sentAt;
}
