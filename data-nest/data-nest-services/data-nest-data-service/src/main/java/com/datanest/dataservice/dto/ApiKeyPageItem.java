package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 分页列表项（Sprint 10 F2）：含绑定 API 数 + 近 7 天调用（0 = 僵尸 Key，建议停用）。
 */
@Data
@Schema(description = "API Key 分页列表项")
public class ApiKeyPageItem {

    @Schema(description = "Key ID")
    private Long id;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "状态：ENABLED 启用 / DISABLED 禁用")
    private String status;

    @Schema(description = "限流 QPS")
    private Integer qpsLimit;

    @Schema(description = "绑定 API 数")
    private Long boundApiCount;

    @Schema(description = "近 7 天调用量（0 = 僵尸 Key）")
    private Long calls7d;

    @Schema(description = "创建人 ID")
    private Long createdBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改人用户名")
    private String updatedByName;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;
}
