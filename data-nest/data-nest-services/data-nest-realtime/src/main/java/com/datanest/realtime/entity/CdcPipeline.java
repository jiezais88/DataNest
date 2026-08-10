package com.datanest.realtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 实时同步管道（Sprint 8 F2）。
 * <p>
 * 管道 = MySQL binlog →（Flink CDC YAML Pipeline，提交到独立 Flink Session 集群）→ Iceberg 湖仓
 * 的同步单元，下挂多条 {@code cdc_pipeline_table} 表级映射。
 * 停止语义 = cancel-with-savepoint（savepoint 落 s3a://datalake/savepoints），
 * 启动时 savepoint_path 有值优先从 savepoint 恢复（不丢不重）。
 */
@Data
@TableName("cdc_pipeline")
public class CdcPipeline {

    /** 状态：已停止 */
    public static final String STATUS_STOPPED = "STOPPED";
    /** 状态：运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：异常 */
    public static final String STATUS_ERROR = "ERROR";

    /** 同步模式：全量+增量（启动位点固定 initial） */
    public static final String SYNC_MODE_FULL_AND_INCREMENT = "FULL_AND_INCREMENT";
    /** 同步模式：仅增量（启动位点 LATEST_OFFSET 默认 / EARLIEST_OFFSET） */
    public static final String SYNC_MODE_INCREMENTAL_ONLY = "INCREMENTAL_ONLY";

    /** 启动位点：全量快照+增量 */
    public static final String STARTUP_MODE_INITIAL = "INITIAL";
    /** 启动位点：从最新位点 */
    public static final String STARTUP_MODE_LATEST_OFFSET = "LATEST_OFFSET";
    /** 启动位点：从最早位点 */
    public static final String STARTUP_MODE_EARLIEST_OFFSET = "EARLIEST_OFFSET";

    /** 写入模式：主键覆盖（每表 primary_key 必填） */
    public static final String WRITE_MODE_UPSERT = "UPSERT";
    /** 写入模式：追加 */
    public static final String WRITE_MODE_APPEND = "APPEND";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 管道名称（唯一） */
    private String name;

    /** 源数据源 ID（engineering datasource_connection.id） */
    private Long sourceDatasourceId;

    /** 源库名（MySQL database） */
    private String sourceDatabase;

    /** 目标库名（Iceberg/Doris catalog 下的 database） */
    private String targetDatabase;

    /** 同步模式：FULL_AND_INCREMENT / INCREMENTAL_ONLY */
    private String syncMode;

    /** 启动位点：INITIAL / LATEST_OFFSET / EARLIEST_OFFSET */
    private String startupMode;

    /** 写入模式：UPSERT / APPEND */
    private String writeMode;

    /** 管道状态：STOPPED / RUNNING / ERROR */
    private String status;

    /** Flink 作业 ID（RUNNING 时有值） */
    private String flinkJobId;

    /** 最近一次 stop-with-savepoint 的 savepoint 路径（启动优先恢复；编辑后清空） */
    private String savepointPath;

    /** 当前同步延迟（秒），监控轮询回写 */
    private Integer currentLagSeconds;

    /** 累计写入变更条数，监控轮询回写 */
    private Long totalChanges;

    /** 最近一次错误信息（截断 2000） */
    private String lastError;

    /** 扩展配置 JSON（预留） */
    private String configJson;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
