package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局 DAG 告警配置
 * 对应表 dag_alert_config
 */
@Data
@TableName("dag_alert_config")
public class DagAlertConfig {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Integer enabled;              // 1 启用，0 关闭

    private String recipients;            // 分号分隔的邮箱

    private String triggerConditions;     // JSON 数组字符串

    private Integer timeoutMinutes;       // 节点超时阈值

    private Long dagId;                   // Sprint 4 review：按 DAG 覆盖；null 表示全局默认

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
