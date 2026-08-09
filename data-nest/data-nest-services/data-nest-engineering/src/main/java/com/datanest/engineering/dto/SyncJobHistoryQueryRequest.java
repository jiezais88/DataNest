package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "同步任务执行历史分页查询请求")
public class SyncJobHistoryQueryRequest {

    @Schema(description = "同步任务 ID 过滤", example = "1234567890123456789")
    private Long syncJobId;
    @Schema(description = "执行状态过滤（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;

    /**
     * 按同步任务名称模糊搜索
     */
    @Schema(description = "关键字（按同步任务名称模糊匹配）")
    private String keyword;

    /**
     * 开始时间下界（ISO 8601 字符串，如 2026-08-02T12:00:00）
     */
    @Schema(description = "开始时间下界（ISO 8601，如 2026-08-02T12:00:00）")
    private String startTimeFrom;

    /**
     * 执行时间上界（ISO 8601 字符串）
     */
    @Schema(description = "执行时间上界（ISO 8601）")
    private String startTimeTo;

    @Schema(description = "页码，从 1 开始")
    private long page = 1;
    @Schema(description = "每页条数")
    private long pageSize = 10;

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getStartTimeFrom() {
        return startTimeFrom;
    }

    public void setStartTimeFrom(String startTimeFrom) {
        this.startTimeFrom = startTimeFrom;
    }

    public String getStartTimeTo() {
        return startTimeTo;
    }

    public void setStartTimeTo(String startTimeTo) {
        this.startTimeTo = startTimeTo;
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
