package com.datanest.governance.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MetadataCommentRequest {

    @NotBlank(message = "注释内容不能为空")
    private String manualComment;
}
