package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Key-管道订阅授权（Sprint 10 F2 建表/实体，F4 WebSocket 实时订阅消费，T7）。
 */
@Data
@TableName("api_key_pipeline")
@Schema(description = "Key-管道订阅授权")
public class ApiKeyPipeline {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "API Key ID")
    private Long keyId;

    @Schema(description = "CDC 管道 ID")
    private Long pipelineId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
