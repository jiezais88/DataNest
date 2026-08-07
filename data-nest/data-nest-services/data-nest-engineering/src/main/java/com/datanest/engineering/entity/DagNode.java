package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点（SQL 任务 / SYNC 任务）
 * 对应表 dag_node
 * config 字段为 String 类型存 JSON（决策 ADR-S3-005），不用 JacksonTypeHandler
 *  JSON 形态：{"type":"SQL","sqlContent":"..."} 或 {"type":"SYNC","syncJobId":123,"syncJobName":"..."}
 */
@Data
@TableName("dag_node")
public class DagNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dagId;

    private String nodeId;               // DAG 内唯一（前端生成 UUID）

    private String nodeName;

    private String nodeType;             // SQL / SYNC

    private Double positionX;

    private Double positionY;

    /**
     * 节点配置 JSON 字符串。
     * SQL:  {"type":"SQL","sqlContent":"SELECT 1"}
     * SYNC: {"type":"SYNC","syncJobId":123,"syncJobName":"xxx"}
     */
    private String config;

    /**
     * DolphinScheduler 任务定义 code，持久化避免节点重命名后 code 变化。
     */
    private Long dsTaskCode;

    /** PowerJob 节点任务 jobId（替代 dsTaskCode，切流后生效，旧列保留至切流清理） */
    private Long powerjobJobId;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
