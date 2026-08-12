package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * API 自动文档（Sprint 10 F2，创建时定义生成、详情页查看/复制调用示例）。
 */
@Data
@Schema(description = "API 自动文档")
public class DataApiDocDTO {

    @Schema(description = "请求方法")
    private String method;

    @Schema(description = "服务内路径（/open-api/v1/{段}）")
    private String path;

    @Schema(description = "经网关完整调用路径（/api/data-service/open-api/v1/{段}）")
    private String fullPath;

    @Schema(description = "认证方式说明")
    private String auth;

    @Schema(description = "调用参数说明")
    private List<DocParam> params;

    @Schema(description = "返回结构说明")
    private String response;

    @Schema(description = "curl 调用示例")
    private String curl;

    /**
     * 文档参数说明。
     */
    @Data
    @Schema(description = "文档参数说明")
    public static class DocParam {

        @Schema(description = "参数名（query）")
        private String name;

        @Schema(description = "说明")
        private String description;
    }
}
