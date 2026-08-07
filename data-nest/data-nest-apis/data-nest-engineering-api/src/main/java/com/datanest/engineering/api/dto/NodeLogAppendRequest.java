package com.datanest.engineering.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 节点执行日志追加请求（服务端按 executionId + nodeId 续号，同事务批量插入）。
 */
@Data
public class NodeLogAppendRequest {

    private Long executionId;

    private List<Entry> entries;

    @Data
    public static class Entry {

        private String level;

        private String message;
    }
}
