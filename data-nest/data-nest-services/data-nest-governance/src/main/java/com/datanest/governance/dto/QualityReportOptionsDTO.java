package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "质量报告筛选联动选项（数据源/库/质量任务）")
@Data
public class QualityReportOptionsDTO {

    @Schema(description = "数据源选项（含内置 Doris）")
    private List<Option> datasources;

    @Schema(description = "库选项（随数据源联动，未选数据源时为全部库；带所属数据源供前端反向联动）")
    private List<DatabaseOption> databases;

    @Schema(description = "质量任务选项（随数据源联动：只列规则覆盖该数据源表的任务）")
    private List<Option> jobs;

    @Schema(description = "通用 id/name 选项")
    @Data
    public static class Option {
        @Schema(description = "ID", example = "1234567890123456789")
        private Long id;
        @Schema(description = "名称")
        private String name;
    }

    @Schema(description = "库选项（库名 + 所属数据源）")
    @Data
    public static class DatabaseOption {
        @Schema(description = "库名")
        private String name;
        @Schema(description = "所属数据源 ID", example = "1234567890123456789")
        private Long datasourceId;
    }
}
