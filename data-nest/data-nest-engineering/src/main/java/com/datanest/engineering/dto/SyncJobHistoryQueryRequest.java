package com.datanest.engineering.dto;

public class SyncJobHistoryQueryRequest {

    private Long syncJobId;
    private String status;
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
