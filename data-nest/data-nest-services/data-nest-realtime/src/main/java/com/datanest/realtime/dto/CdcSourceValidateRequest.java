package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "CDC 源数据源预检请求")
@Data
public class CdcSourceValidateRequest {

    @Schema(description = "源数据源 ID", example = "2083088527209295874")
    private Long datasourceId;

    @Schema(description = "源库名（可空，非空时校验库存在性）", example = "testdb")
    private String sourceDatabase;
}
