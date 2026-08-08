package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点执行批量乐观锁更新请求。
 * <p>
 * 保留 version 冲突语义：WHERE 按 (id, version) 成对匹配并 version+1，
 * version 不匹配的行跳过不写，响应返回失败（被跳过）的 id 列表。
 */
@Data
public class NodeExecutionBatchUpdateRequest {

    private List<UpdateItem> updates;

    @Data
    public static class UpdateItem {

        private Long id;

        /** 更新时期望的当前 version（乐观锁） */
        private Integer version;

        private String status;

        /** PowerJob 任务实例 ID（旧 dsTaskInstanceId 已随 P4 切流清理删除） */
        private Long powerjobInstanceId;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Long durationMs;

        private String errorMessage;

        private String outputInfo;

        private Long syncJobHistoryId;
    }
}
