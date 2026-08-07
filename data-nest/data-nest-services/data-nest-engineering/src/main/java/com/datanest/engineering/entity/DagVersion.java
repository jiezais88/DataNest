package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 版本快照
 * 对应表 dag_version
 */
@Data
@TableName("dag_version")
public class DagVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dagId;

    private Integer versionNo;

    private String snapshot;

    private String changeSummary;

    private Long createdBy;

    private LocalDateTime createdAt;
}
