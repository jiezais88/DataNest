package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表配置负责人请求（ownerUserId 为 null 表示清除负责人）")
@Data
public class AssignOwnerRequest {

    @Schema(description = "负责人用户 ID", example = "1234567890123456789")
    private Long ownerUserId;
}
