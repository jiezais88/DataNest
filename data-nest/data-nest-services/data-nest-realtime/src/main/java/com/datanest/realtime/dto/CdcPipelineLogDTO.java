package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "CDC 管道运行日志")
@Data
public class CdcPipelineLogDTO {

    @Schema(description = "日志 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "日志级别：INFO / WARN / ERROR")
    private String level;

    @Schema(description = "日志内容")
    private String message;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
