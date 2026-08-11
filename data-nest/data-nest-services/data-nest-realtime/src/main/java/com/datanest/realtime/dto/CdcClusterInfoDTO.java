package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Flink 集群容量信息（向导并行度提示用；集群不可达时字段为空，前端降级通用提示）")
@Data
public class CdcClusterInfoDTO {

    @Schema(description = "Task Slot 总数")
    private Integer slotsTotal;

    @Schema(description = "空闲 Task Slot 数")
    private Integer slotsAvailable;
}
