package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * CDC 管道实时 KPI + 趋势（Sprint 9 F1 运行监控页签）。
 */
public class CdcPipelineMetricsDTO {

    @Schema(description = "管道实时 KPI")
    @Data
    public static class Current {
        @Schema(description = "是否运行中（非 RUNNING 时各指标为最后已知值）")
        private Boolean live;
        @Schema(description = "当前延迟（秒），-1 表示取不到")
        private Integer currentLagSeconds;
        @Schema(description = "当前吞吐（行/秒，sink vertex numRecordsOutPerSecond 求和），-1 表示取不到")
        private Double throughputRowsPerSecond;
        @Schema(description = "作业累计重启次数")
        private Integer numRestarts;
        @Schema(description = "累计变更数")
        private Long totalChanges;
    }

    @Schema(description = "趋势图数据点")
    @Data
    public static class TrendPoint {
        @Schema(description = "采样时间（1h/6h 原始分钟点；24h 为 5 分钟桶起点；7d 为整点）")
        private String minuteAt;
        @Schema(description = "本桶延迟均值（秒），无样本为 null")
        private Integer lagAvgSeconds;
        @Schema(description = "本桶延迟峰值（秒），无样本为 null（趋势图标红判定用）")
        private Integer lagMaxSeconds;
        @Schema(description = "本桶吞吐均值（行/秒），无样本为 null")
        private Double recordsPerSecondAvg;
    }

    @Schema(description = "趋势图返回（range 分桶聚合结果）")
    @Data
    public static class Trend {
        @Schema(description = "查询范围：1h/6h/24h/7d")
        private String range;
        @Schema(description = "数据点（按时间升序；无数据时段为空，前端断点展示不插值）")
        private List<TrendPoint> points;
    }
}
