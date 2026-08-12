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
 * 创建 API Key 请求（Sprint 10 F2）：明文仅创建响应展示一次，后端只存 SHA-256 哈希。
 */
@Data
@Schema(description = "创建 API Key 请求")
public class ApiKeyCreateRequest {

    @Schema(description = "Key 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Key 名称不能为空")
    @Size(max = 100, message = "Key 名称最长 100 字符")
    private String name;

    @Schema(description = "限流 QPS（该 Key 下所有 API 共享，F3 生效）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "限流 QPS 不能为空")
    @Min(value = 1, message = "限流 QPS 最小 1")
    @Max(value = 10000, message = "限流 QPS 最大 10000")
    private Integer qpsLimit;

    @Schema(description = "绑定的 API ID 列表")
    private List<Long> apiIds;
}
