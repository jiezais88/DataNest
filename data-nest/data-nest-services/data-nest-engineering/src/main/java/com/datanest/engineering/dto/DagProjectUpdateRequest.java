package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "DAG 项目更新请求")
@Data
public class DagProjectUpdateRequest {
    @Schema(description = "项目名称")
    @NotBlank
    @Size(max = 100)
    private String name;

    @Schema(description = "项目描述")
    @Size(max = 1000)
    private String description;
}
