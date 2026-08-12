package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "数据源连接状态统计（列表页顶部统计卡）")
@Data
public class DataSourceStatsDTO {

    @Schema(description = "连接正常数")
    private Long normal;

    @Schema(description = "连接异常数")
    private Long error;

    @Schema(description = "已下线数")
    private Long offline;

    @Schema(description = "未检测数")
    private Long unknown;
}
