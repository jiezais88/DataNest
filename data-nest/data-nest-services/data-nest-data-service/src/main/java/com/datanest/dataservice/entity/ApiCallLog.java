package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 调用统计明细（Sprint 10 F3 由 OpenApiKeyFilter 异步写入；F2 仅用于近 7 天调用聚合查询）。
 */
@Data
@TableName("api_call_log")
@Schema(description = "API 调用统计明细")
public class ApiCallLog {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "数据 API ID")
    private Long apiId;

    @Schema(description = "API Key ID")
    private Long keyId;

    @Schema(description = "Key 名称快照（写入时冗余；Key 物理删除后统计仍可显示原名）")
    private String keyName;

    @Schema(description = "HTTP 状态码")
    private Integer statusCode;

    @Schema(description = "耗时毫秒")
    private Integer durationMs;

    @Schema(description = "调用时间")
    private LocalDateTime createdAt;
}
