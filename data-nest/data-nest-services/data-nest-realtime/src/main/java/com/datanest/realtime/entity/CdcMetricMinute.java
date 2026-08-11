package com.datanest.realtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 管道分钟级指标历史（Sprint 9 F1）。
 * <p>
 * 5s 监控轮询在内存聚合，每分钟对当前整分钟 upsert 一行（pipeline_id + minute_at 唯一）；
 * 保留 30 天（retention-days 可配），由 MetricRetentionCleaner 每日清理。
 * 本表为 append/upsert 型快照，无 updated_at（覆盖写非业务更新语义）。
 */
@Data
@TableName("cdc_metric_minute")
public class CdcMetricMinute {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 管道 ID */
    private Long pipelineId;

    /** 采样分钟（截断到分） */
    private LocalDateTime minuteAt;

    /** 本分钟延迟均值（秒）；无有效样本为 NULL */
    private Integer lagAvgSeconds;

    /** 本分钟延迟峰值（秒），趋势图标红判定用 */
    private Integer lagMaxSeconds;

    /** 本分钟吞吐均值（行/秒） */
    private Double recordsPerSecondAvg;

    /** 作业累计重启次数（该分钟最后一次采样值） */
    private Integer numRestarts;

    /** 累计变更数（该分钟最后一次采样值） */
    private Long totalChanges;

    private LocalDateTime createdAt;
}
