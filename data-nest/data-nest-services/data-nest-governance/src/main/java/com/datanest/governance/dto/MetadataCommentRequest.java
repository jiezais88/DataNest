package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "元数据人工注释更新请求")
@Data
public class MetadataCommentRequest {

    @Schema(description = "人工注释内容")
    @NotBlank(message = "注释内容不能为空")
    private String manualComment;
}
