package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 版本 DTO（列表、对比、回滚）
 */
@Schema(description = "DAG 版本 DTO（列表、对比、回滚）")
@Data
public class DagVersionPayload {

    @Schema(description = "版本 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long dagId;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "版本快照 JSON")
    private String snapshot;

    @Schema(description = "变更摘要")
    private String changeSummary;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    /** 版本对比结果（仅 compare 接口使用） */
    @Schema(description = "版本对比结果（仅 compare 接口返回）")
    private DagVersionDiff diff;

    /**
     * 版本差异结构
     */
    @Schema(description = "版本差异结构")
    @Data
    public static class DagVersionDiff {

        @Schema(description = "新增节点列表")
        private List<String> addedNodes;
        @Schema(description = "删除节点列表")
        private List<String> removedNodes;
        @Schema(description = "修改节点列表")
        private List<String> modifiedNodes;

        @Schema(description = "新增边列表")
        private List<String> addedEdges;
        @Schema(description = "删除边列表")
        private List<String> removedEdges;

        @Schema(description = "新增参数列表")
        private List<String> addedParams;
        @Schema(description = "删除参数列表")
        private List<String> removedParams;
        @Schema(description = "修改参数列表")
        private List<String> modifiedParams;
    }
}
