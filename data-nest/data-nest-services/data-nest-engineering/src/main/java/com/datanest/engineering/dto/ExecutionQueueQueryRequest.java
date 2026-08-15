package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 执行队列分页查询请求（Sprint 11 F3）
 * <p>
 * 与同步任务 SyncJobQueryRequest 风格一致（关键字 + page + pageSize），复用 usePagedList。
 */
@Schema(description = "执行队列分页查询请求")
public class ExecutionQueueQueryRequest {

    @Schema(description = "关键字（模糊匹配队列名/描述）")
    private String keyword;

    @Schema(description = "页码，从 1 开始")
    private long page = 1;

    @Schema(description = "每页条数")
    private long pageSize = 10;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }
}