package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * CDC 管道 Checkpoint / Savepoint 管理（Sprint 9 F2 检查点页签）。
 * <p>
 * 数据实时转发 Flink REST /jobs/{id}/checkpoints，不落库（T5）；
 * 作业不可达返回 reachable=false 空结构，前端降级提示。
 */
public class CdcPipelineCheckpointsDTO {

    @Schema(description = "检查点页签数据")
    @Data
    public static class Checkpoints {
        @Schema(description = "Flink 作业是否可达（不可达时三卡/历史为空，前端降级提示）")
        private Boolean reachable;
        @Schema(description = "健康度摘要（三卡）")
        private Summary summary;
        @Schema(description = "最近 20 条 checkpoint 历史（按触发时间倒序）")
        private List<HistoryItem> history;
        @Schema(description = "最近一次 savepoint 路径（latest.savepoint.external_path），无则 null")
        private String latestSavepointPath;
    }

    @Schema(description = "checkpoint 健康度摘要")
    @Data
    public static class Summary {
        @Schema(description = "最近一次成功 checkpoint 触发时间（yyyy-MM-dd HH:mm:ss），无则 null")
        private String latestCompletedTime;
        @Schema(description = "端到端耗时均值（毫秒），无样本为 null")
        private Long avgDurationMs;
        @Schema(description = "近期失败次数（受 Flink 保留窗口限制，文案标注「近期」不承诺精确 24h）")
        private Long recentFailedCount;
    }

    @Schema(description = "checkpoint 历史条目")
    @Data
    public static class HistoryItem {
        @Schema(description = "触发时间（yyyy-MM-dd HH:mm:ss）")
        private String triggerTime;
        @Schema(description = "端到端耗时（毫秒）")
        private Long durationMs;
        @Schema(description = "状态大小（字节）")
        private Long stateSizeBytes;
        @Schema(description = "状态：COMPLETED / FAILED / IN_PROGRESS")
        private String status;
        @Schema(description = "是否 savepoint")
        private Boolean savepoint;
        @Schema(description = "checkpoint 类型：CHECKPOINT / SAVEPOINT")
        private String checkpointType;
    }

    @Schema(description = "手动触发 savepoint 返回")
    @Data
    public static class SavepointResult {
        @Schema(description = "新 savepoint 路径（s3a://...，已回写管道 savepoint_path）")
        private String savepointPath;
    }
}
