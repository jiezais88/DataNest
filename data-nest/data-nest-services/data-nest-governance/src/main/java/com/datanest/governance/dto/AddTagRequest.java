package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "打标签请求")
@Data
public class AddTagRequest {

    @Schema(description = "标签名（已存在则复用，否则新建；≤100 字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tagName;
}
