package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 版本 DTO（列表、对比、回滚）
 */
@Data
public class DagVersionPayload {

    private Long id;

    private Long dagId;

    private Integer versionNo;

    private String snapshot;

    private String changeSummary;

    private Long createdBy;

    private LocalDateTime createdAt;

    /** 版本对比结果（仅 compare 接口使用） */
    private DagVersionDiff diff;

    /**
     * 版本差异结构
     */
    @Data
    public static class DagVersionDiff {

        private List<String> addedNodes;
        private List<String> removedNodes;
        private List<String> modifiedNodes;

        private List<String> addedEdges;
        private List<String> removedEdges;

        private List<String> addedParams;
        private List<String> removedParams;
        private List<String> modifiedParams;
    }
}
