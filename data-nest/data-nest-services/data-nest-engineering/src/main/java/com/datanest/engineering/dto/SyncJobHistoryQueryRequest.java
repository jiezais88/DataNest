package com.datanest.engineering.dto;

public class SyncJobHistoryQueryRequest {

    private Long syncJobId;
    private String status;

    /**
     * 按同步任务名称模糊搜索
     */
    private String keyword;

    /**
     * 开始时间下界（ISO 8601 字符串，如 2026-08-02T12:00:00）
     */
    private String startTimeFrom;

    /**
     * 执行时间上界（ISO 8601 字符串）
     */
    private String startTimeTo;

    private long page = 1;
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
