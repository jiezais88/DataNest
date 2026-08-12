package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建 API Key 响应（Sprint 10 F2）：apiKey 明文仅本次返回，请立即保存。
 */
@Data
@Schema(description = "创建 API Key 响应（明文仅展示一次）")
public class ApiKeyCreateResult {

    @Schema(description = "Key ID")
    private Long id;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "API Key 明文（K- 前缀，仅本次返回，后端不存明文）")
    private String apiKey;

    @Schema(description = "限流 QPS")
    private Integer qpsLimit;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
