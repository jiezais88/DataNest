package com.datanest.engineering.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class SyncJobHistoryQueryRequest {

    private Long syncJobId;
    private String status;

    /**
     * 按同步任务名称模糊搜索
     */
    private String keyword;

    @NotNull(message = "开始时间起不能为空")
    private LocalDateTime startTimeFrom;

    @NotNull(message = "开始时间止不能为空")
    private LocalDateTime startTimeTo;

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

    public LocalDateTime getStartTimeFrom() {
        return startTimeFrom;
    }

    public void setStartTimeFrom(LocalDateTime startTimeFrom) {
        this.startTimeFrom = startTimeFrom;
    }

    public LocalDateTime getStartTimeTo() {
        return startTimeTo;
    }

    public void setStartTimeTo(LocalDateTime startTimeTo) {
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
