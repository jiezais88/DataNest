package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sync_job_log")
public class SyncJobLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long historyId;

    private Long syncJobId;

    private String level;

    private String message;

    private Integer lineNum;

    /** 所属表名；平台概要行（开始/成功/失败）为 NULL，归「概览」 */
    private String tableName;

    private LocalDateTime createdAt;
}
