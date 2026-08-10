package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "发表评论请求")
@Data
public class AddCommentRequest {

    @Schema(description = "评论内容（≤2000 字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;
}
