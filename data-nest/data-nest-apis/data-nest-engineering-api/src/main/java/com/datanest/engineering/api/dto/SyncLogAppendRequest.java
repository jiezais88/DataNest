package com.datanest.engineering.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 同步执行日志追加请求。
 * <p>
 * 行号由服务端续号（line_num = 现有行数 + 序号），一次事务批量插入，
 * 500 条/批分片由服务端处理，调用方无需先读 nextLineNum。
 */
@Data
public class SyncLogAppendRequest {

    private List<Entry> entries;

    /**
     * 日志行。level/tableName 可空：level 缺省由内容推断（含 ERROR/WARN 关键字），
     * tableName 缺省为 NULL（归「概览」）。
     */
    @Data
    public static class Entry {

        private String content;

        private String level;

        private String tableName;
    }
}
