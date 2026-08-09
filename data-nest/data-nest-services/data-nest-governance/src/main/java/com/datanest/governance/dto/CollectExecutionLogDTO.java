package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "采集执行日志")
@Data
public class CollectExecutionLogDTO {

    @Schema(description = "日志 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "采集历史 ID", example = "1234567890123456789")
    private Long historyId;

    @Schema(description = "采集任务 ID", example = "1234567890123456789")
    private Long taskId;

    @Schema(description = "日志级别")
    private String level;

    @Schema(description = "日志内容")
    private String message;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
