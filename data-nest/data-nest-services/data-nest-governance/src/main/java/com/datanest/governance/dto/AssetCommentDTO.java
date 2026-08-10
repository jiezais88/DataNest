package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "资产评论列表项")
@Data
public class AssetCommentDTO {

    @Schema(description = "评论 ID", example = "1234567890123456789")
    private Long commentId;

    @Schema(description = "表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "评论人用户 ID", example = "1234567890123456789")
    private Long userId;

    @Schema(description = "评论人用户名（用户已注销显示「已注销」；用户服务不可用降级「—」）")
    private String username;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "发表时间（ISO 8601）")
    private LocalDateTime createdAt;
}
