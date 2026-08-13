package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑 API Key 请求（Sprint 10 F2）：改名 / 限流 QPS / 重绑 API。
 */
@Data
@Schema(description = "编辑 API Key 请求")
public class ApiKeyUpdateRequest {

    @Schema(description = "Key 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Key 名称不能为空")
    @Size(max = 100, message = "Key 名称最长 100 字符")
    private String name;

    @Schema(description = "限流 QPS", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "限流 QPS 不能为空")
    @Min(value = 1, message = "限流 QPS 最小 1")
    @Max(value = 10000, message = "限流 QPS 最大 10000")
    private Integer qpsLimit;

    @Schema(description = "绑定的 API ID 列表（全量重绑）")
    private List<Long> apiIds;

    @Schema(description = "绑定的 CDC 管道 ID 列表（全量重绑，F4 WebSocket 订阅授权）")
    private List<Long> pipelineIds;
}
