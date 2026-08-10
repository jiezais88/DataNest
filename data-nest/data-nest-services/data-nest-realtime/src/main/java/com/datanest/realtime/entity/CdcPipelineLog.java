package com.datanest.realtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 管道运行日志（创建/启停/状态变更/延迟告警）。
 */
@Data
@TableName("cdc_pipeline_log")
public class CdcPipelineLog {

    /** 级别：信息 */
    public static final String LEVEL_INFO = "INFO";
    /** 级别：警告 */
    public static final String LEVEL_WARN = "WARN";
    /** 级别：错误 */
    public static final String LEVEL_ERROR = "ERROR";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属管道 ID */
    private Long pipelineId;

    /** 日志级别：INFO / WARN / ERROR */
    private String level;

    /** 日志内容 */
    private String message;

    private LocalDateTime createdAt;
}
