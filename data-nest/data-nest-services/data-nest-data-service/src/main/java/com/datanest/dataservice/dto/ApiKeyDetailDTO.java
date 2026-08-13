package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API Key 详情（Sprint 10 F2，编辑弹窗预填当前绑定 API 用；明文 Key 只在创建时返回，详情不含）。
 */
@Data
@Schema(description = "API Key 详情")
public class ApiKeyDetailDTO {

    @Schema(description = "Key ID")
    private Long id;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "状态：ENABLED 启用 / DISABLED 禁用")
    private String status;

    @Schema(description = "限流 QPS")
    private Integer qpsLimit;

    @Schema(description = "当前绑定的 API ID 列表")
    private List<Long> apiIds;

    @Schema(description = "当前绑定的 CDC 管道 ID 列表（F4 WebSocket 订阅授权）")
    private List<Long> pipelineIds;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
