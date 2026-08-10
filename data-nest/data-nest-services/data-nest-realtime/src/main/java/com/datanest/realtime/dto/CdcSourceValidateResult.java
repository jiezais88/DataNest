package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "CDC 源数据源预检结果")
@Data
public class CdcSourceValidateResult {

    @Schema(description = "是否全部通过")
    private Boolean success;

    @Schema(description = "逐项检查结果")
    private List<CheckItem> checks;

    @Schema(description = "单项检查")
    @Data
    public static class CheckItem {

        @Schema(description = "检查项名称", example = "binlog 开启")
        private String name;

        @Schema(description = "是否通过")
        private Boolean passed;

        @Schema(description = "检查说明/失败原因")
        private String message;

        public CheckItem() {
        }

        public CheckItem(String name, Boolean passed, String message) {
            this.name = name;
            this.passed = passed;
            this.message = message;
        }
    }
}
