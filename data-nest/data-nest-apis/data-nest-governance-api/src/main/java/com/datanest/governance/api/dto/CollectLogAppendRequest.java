package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 追加采集执行日志请求（批量插入 collect_execution_log；
 * taskId 由服务端按 historyId 从历史记录带出，对齐 CollectExecutor.log 的语义）。
 */
@Data
public class CollectLogAppendRequest {

    private List<Entry> entries;

    @Data
    public static class Entry {

        /** 日志级别（INFO/ERROR 等） */
        private String level;

        /** 日志内容 */
        private String message;
    }
}
